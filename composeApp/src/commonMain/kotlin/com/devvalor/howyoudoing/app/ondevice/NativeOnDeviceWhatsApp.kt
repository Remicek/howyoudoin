package com.devvalor.howyoudoing.app.ondevice

import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceHistoryError
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceHistoryStatus
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.ondevice.OnDevicePairing
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** hydbridge's WhatsApp client as each platform's bindings expose it. Calls throw on failure. */
interface NativeWhatsAppClient {
    fun isLinked(): Boolean
    fun connect(phoneNumber: String)
    fun disconnect()
    fun logout()
    fun sendText(chatJid: String, text: String): String
    fun sendReaction(chatJid: String, messageId: String, senderJid: String, emoji: String)
    fun markRead(chatJid: String, messageId: String, senderJid: String)
    fun setTyping(chatJid: String, isTyping: Boolean)
    fun requestHistory(chatJid: String, oldestMessageId: String, oldestIsOutgoing: Boolean, oldestTimestampMs: Long, count: Long)
    fun sendMediaFile(chatJid: String, path: String, mimeType: String, fileName: String, caption: String, width: Long, height: Long): String
}

fun interface NativeWhatsAppFactory {
    fun create(dataDir: String, onEvent: (String) -> Unit): NativeWhatsAppClient
}

/** WhatsApp running in-process through hydbridge, shared by Android and iOS. */
class NativeOnDeviceWhatsApp(
    private val factory: NativeWhatsAppFactory,
    private val dataDir: OnDeviceDataDirectory,
    scope: CoroutineScope
) : OnDeviceWhatsApp {

    private val lock = Mutex()
    private var client: NativeWhatsAppClient? = null

    private val _state = MutableStateFlow<OnDeviceMessengerState>(OnDeviceMessengerState.NotLinked)
    override val state: StateFlow<OnDeviceMessengerState> = _state.asStateFlow()

    private val _pairing = MutableStateFlow<OnDevicePairing?>(null)
    override val pairing: StateFlow<OnDevicePairing?> = _pairing.asStateFlow()

    private val _events = MutableSharedFlow<String>(replay = EVENT_REPLAY, extraBufferCapacity = 256)
    override val events: Flow<String> = _events.asSharedFlow()

    override val isSupported = true

    init {
        scope.launch { connectIfLinked() }
    }

    override suspend fun connectIfLinked() {
        lock.withLock {
            withContext(Dispatchers.IO) {
                val current = client ?: runCatching { factory.create(dataDir.path, ::onNativeEvent) }.getOrNull()?.also { client = it }
                if (current == null || !current.isLinked()) return@withContext
                _state.value = OnDeviceMessengerState.Connecting
                runCatching { current.connect("") }.onFailure { failed(it) }
            }
        }
    }

    override suspend fun startLinking(phoneNumber: String?): EmptyResult<DataError.Remote> = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                client?.disconnect()
                _pairing.value = null
                _events.resetReplayCache()
                val created = client ?: factory.create(dataDir.path, ::onNativeEvent).also { client = it }
                _state.value = OnDeviceMessengerState.Connecting
                created.connect(phoneNumber.orEmpty())
                Result.Success(Unit)
            } catch (e: Exception) {
                failed(e)
                Result.Failure(DataError.Remote.UNKNOWN)
            }
        }
    }

    override suspend fun cancelLinking() {
        lock.withLock {
            withContext(Dispatchers.IO) { client?.disconnect() }
            _pairing.value = null
            if (_state.value !is OnDeviceMessengerState.Connected) _state.value = OnDeviceMessengerState.NotLinked
        }
    }

    override suspend fun unlink() {
        lock.withLock {
            withContext(Dispatchers.IO) { runCatching { client?.logout() } }
            client = null
            dataDir.clear()
            _events.resetReplayCache()
            _pairing.value = null
            _state.value = OnDeviceMessengerState.NotLinked
        }
    }

    // WhatsApp links by pairing, not with web cookies
    override suspend fun link(cookies: Map<String, String>): EmptyResult<DataError.Remote> = Result.Failure(DataError.Remote.BAD_REQUEST)

    override suspend fun sendText(threadKey: String, text: String, isEncrypted: Boolean, isGroup: Boolean): Result<String, DataError.Remote> =
        native("Sending a message") { sendText(threadKey, text) }

    override suspend fun sendAttachment(
        threadKey: String, bytes: ByteArray, fileName: String, mimeType: String, width: Int?, height: Int?, caption: String?
    ): Result<String, DataError.Remote> = native("Sending an attachment") {
        val path = dataDir.writeTemp(bytes)
        sendMediaFile(threadKey, path, mimeType, fileName, caption.orEmpty(), (width ?: 0).toLong(), (height ?: 0).toLong())
    }

    override suspend fun sendReaction(
        threadKey: String, messageId: String, emoji: String, isEncrypted: Boolean, isGroup: Boolean, messageSenderId: String
    ): EmptyResult<DataError.Remote> = native("Sending a reaction") { sendReaction(threadKey, messageId, messageSenderId, emoji) }

    override suspend fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String): EmptyResult<DataError.Remote> =
        native("Marking read") { markRead(threadKey, lastMessageId, senderId) }

    override suspend fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean): EmptyResult<DataError.Remote> =
        native("Sending typing") { setTyping(threadKey, isTyping) }

    // Older messages come from the user's own phone as an on-demand history sync
    override suspend fun requestOlder(
        threadKey: String, beforeTimestampMs: Long, beforeMessageId: String, beforeMessageIsOutgoing: Boolean
    ): EmptyResult<DataError.Remote> = native("Requesting history") {
        requestHistory(threadKey, beforeMessageId, beforeMessageIsOutgoing, beforeTimestampMs, HISTORY_PAGE_SIZE)
    }

    override suspend fun historyStatus(): Result<OnDeviceHistoryStatus, DataError.Remote> =
        Result.Success(OnDeviceHistoryStatus(isRestored = false, isBackupAvailable = false))

    override suspend fun restoreHistory(pin: String): EmptyResult<OnDeviceHistoryError> = Result.Failure(OnDeviceHistoryError.NoBackup)

    override suspend fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    private suspend fun <T> native(description: String, block: NativeWhatsAppClient.() -> T): Result<T, DataError.Remote> {
        val current = client ?: return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        return withContext(Dispatchers.IO) {
            try {
                Result.Success(current.block()).also { log("$description: ok") }
            } catch (e: Exception) {
                log("$description failed: ${e.message}")
                Result.Failure(DataError.Remote.UNKNOWN)
            }
        }
    }

    private fun failed(e: Throwable) {
        log("Connecting failed: ${e.message}")
        _state.value = OnDeviceMessengerState.Failed(e.message ?: "Couldn't connect")
    }

    private fun onNativeEvent(json: String) {
        val event = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        fun str(key: String) = event[key]?.jsonPrimitive?.contentOrNull
        when (str("type")) {
            "pairing" -> _pairing.value = OnDevicePairing(qrCode = str("qr"), pairingCode = str("code"))
            "debug" -> log("debug $json")
            "self" -> {
                _pairing.value = null
                _state.value = OnDeviceMessengerState.Connected(str("name")?.takeIf { it.isNotBlank() })
            }
            "state" -> {
                log("state ${str("state")} ${str("error").orEmpty()}")
                when (str("state")) {
                    "reconnecting" -> if (_state.value is OnDeviceMessengerState.Connected) _state.value = OnDeviceMessengerState.Connecting
                    "pairing_failed" -> if (_state.value !is OnDeviceMessengerState.Connected) {
                        _pairing.value = null
                        _state.value = OnDeviceMessengerState.Failed(
                            when (val reason = str("error").orEmpty()) {
                                "", "timeout" -> "Linking timed out, try again"
                                else -> "Linking failed: $reason"
                            }
                        )
                    }
                    "logged_out" -> {
                        _state.value = OnDeviceMessengerState.NotLinked
                        dataDir.clear()
                    }
                }
            }
        }
        _events.tryEmit(json)
    }

    private fun log(message: String) = println("OnDeviceWhatsApp: $message")

    private companion object {
        const val EVENT_REPLAY = 5_000
        const val HISTORY_PAGE_SIZE = 50L
    }
}
