package com.devvalor.howyoudoing.app.ondevice

import android.content.Context
import android.util.Log
import java.io.File
import com.devvalor.howyoudoing.bridge.hydbridge.Hydbridge
import com.devvalor.howyoudoing.bridge.hydbridge.Listener
import com.devvalor.howyoudoing.bridge.hydbridge.Messenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceHistoryError
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceHistoryStatus
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs Messenger in-process through the gomobile-built hydbridge library (bridge-mobile/).
 * Cookies are kept in app-private SharedPreferences.
 * TODO: move them to EncryptedSharedPreferences / Keystore before this leaves the experiment stage.
 */
class GoOnDeviceMessenger(
    context: Context,
    private val scope: CoroutineScope
) : OnDeviceMessenger {

    private val prefs = context.getSharedPreferences("on_device_messenger", Context.MODE_PRIVATE)

    /** Encrypted-chat keys (whatsmeow store) and the history device; deleted on unlink. */
    private val dataDir = File(context.noBackupFilesDir, "on_device_messenger").apply { mkdirs() }
    private val lock = Mutex()
    private var messenger: Messenger? = null

    private val _state = MutableStateFlow<OnDeviceMessengerState>(OnDeviceMessengerState.NotLinked)
    override val state: StateFlow<OnDeviceMessengerState> = _state.asStateFlow()

    // The inbox arrives as one burst on connect; replay it so screens opened later still get it
    private val _events = MutableSharedFlow<String>(replay = EVENT_REPLAY, extraBufferCapacity = 256)
    override val events: Flow<String> = _events.asSharedFlow()

    override val isSupported = true

    private val listener = object : Listener {
        override fun onEvent(json: String) {
            onNativeEvent(json)
        }
    }

    init {
        scope.launch { connectIfLinked() }
    }

    override suspend fun link(cookies: Map<String, String>): EmptyResult<DataError.Remote> {
        val json = JsonObject(cookies.mapValues { JsonPrimitive(it.value) }).toString()
        val result = connect(json)
        if (result is Result.Success) prefs.edit().putString(KEY_COOKIES, json).apply()
        return result
    }

    override suspend fun connectIfLinked() {
        val saved = prefs.getString(KEY_COOKIES, null) ?: return
        connect(saved)
    }

    private suspend fun connect(cookiesJson: String): EmptyResult<DataError.Remote> = lock.withLock {
        withContext(Dispatchers.IO) {
            messenger?.disconnect()
            messenger = null
            _events.resetReplayCache()
            _state.value = OnDeviceMessengerState.Connecting
            try {
                val client = Hydbridge.newMessenger(cookiesJson, dataDir.absolutePath, listener)
                client.connect()
                messenger = client
                Result.Success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "Connecting on-device Messenger failed", e)
                _state.value = OnDeviceMessengerState.Failed(e.message ?: "Couldn't connect")
                Result.Failure(DataError.Remote.UNKNOWN)
            }
        }
    }

    override suspend fun sendText(threadKey: String, text: String, isEncrypted: Boolean, isGroup: Boolean): Result<String, DataError.Remote> {
        val client = messenger ?: return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        return withContext(Dispatchers.IO) {
            try {
                Result.Success(
                    if (isEncrypted) client.sendEncryptedText(threadKey, isGroup, text) else client.sendText(threadKey, text)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Sending on-device Messenger message failed", e)
                Result.Failure(DataError.Remote.UNKNOWN)
            }
        }
    }

    override suspend fun historyStatus(): Result<OnDeviceHistoryStatus, DataError.Remote> {
        val client = messenger ?: return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        return withContext(Dispatchers.IO) {
            try {
                val status = Json.parseToJsonElement(client.historyStatus()).jsonObject
                Result.Success(
                    OnDeviceHistoryStatus(
                        isRestored = status["restored"]?.jsonPrimitive?.booleanOrNull == true,
                        isBackupAvailable = status["backupAvailable"]?.jsonPrimitive?.booleanOrNull == true
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Checking on-device Messenger history failed", e)
                Result.Failure(DataError.Remote.UNKNOWN)
            }
        }
    }

    override suspend fun restoreHistory(pin: String): EmptyResult<OnDeviceHistoryError> {
        val client = messenger ?: return Result.Failure(OnDeviceHistoryError.Other("Messenger isn't connected"))
        return withContext(Dispatchers.IO) {
            try {
                client.restoreHistory(pin)
                Result.Success(Unit)
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                Result.Failure(
                    when {
                        message.startsWith("wrong_pin:") ->
                            OnDeviceHistoryError.WrongPin(message.substringAfter(':').toIntOrNull() ?: 0)
                        message == "pin_limit" -> OnDeviceHistoryError.NoAttemptsLeft
                        message == "no_backup" -> OnDeviceHistoryError.NoBackup
                        else -> OnDeviceHistoryError.Other(message)
                    }
                )
            }
        }
    }

    override suspend fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long): EmptyResult<DataError.Remote> {
        val client = messenger ?: return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        return withContext(Dispatchers.IO) {
            try {
                client.fetchEncryptedHistory(threadKey, isGroup, beforeTimestampMs, HISTORY_PAGE_SIZE)
                Result.Success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "Fetching encrypted Messenger history failed", e)
                Result.Failure(DataError.Remote.UNKNOWN)
            }
        }
    }

    override suspend fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String): EmptyResult<DataError.Remote> {
        val client = messenger ?: return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        return withContext(Dispatchers.IO) {
            try {
                client.requestOlder(threadKey, beforeTimestampMs, beforeMessageId)
                Result.Success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "Requesting older on-device Messenger messages failed", e)
                Result.Failure(DataError.Remote.UNKNOWN)
            }
        }
    }

    override suspend fun sendReaction(threadKey: String, messageId: String, emoji: String): EmptyResult<DataError.Remote> =
        call("Sending on-device Messenger reaction") { sendReaction(threadKey, messageId, emoji) }

    override suspend fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String): EmptyResult<DataError.Remote> =
        call("Marking on-device Messenger chat read") { markRead(threadKey, isEncrypted, isGroup, lastMessageId, senderId) }

    override suspend fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean): EmptyResult<DataError.Remote> =
        call("Sending on-device Messenger typing") { setTyping(threadKey, isEncrypted, isGroup, isTyping) }

    /** Runs a native call off the main thread, mapping exceptions to a failure. */
    private suspend fun call(description: String, block: Messenger.() -> Unit): EmptyResult<DataError.Remote> {
        val client = messenger ?: return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        return withContext(Dispatchers.IO) {
            try {
                client.block()
                Result.Success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "$description failed", e)
                Result.Failure(DataError.Remote.UNKNOWN)
            }
        }
    }

    override suspend fun unlink() {
        lock.withLock {
            withContext(Dispatchers.IO) { messenger?.disconnect() }
            messenger = null
            prefs.edit().remove(KEY_COOKIES).apply()
            dataDir.deleteRecursively()
            dataDir.mkdirs()
            _events.resetReplayCache()
            _state.value = OnDeviceMessengerState.NotLinked
        }
    }

    private fun onNativeEvent(json: String) {
        val event = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "self" -> _state.value = OnDeviceMessengerState.Connected(event["name"]?.jsonPrimitive?.contentOrNull)
            "state" -> when (event["state"]?.jsonPrimitive?.contentOrNull) {
                "error" -> _state.value = OnDeviceMessengerState.Failed(event["error"]?.jsonPrimitive?.contentOrNull ?: "Disconnected")
                "reconnecting" -> _state.value = OnDeviceMessengerState.Connecting
            }
        }
        _events.tryEmit(json)
    }

    private companion object {
        const val TAG = "OnDeviceMessenger"
        const val KEY_COOKIES = "cookies"
        const val EVENT_REPLAY = 5_000
        const val HISTORY_PAGE_SIZE = 50L
    }
}
