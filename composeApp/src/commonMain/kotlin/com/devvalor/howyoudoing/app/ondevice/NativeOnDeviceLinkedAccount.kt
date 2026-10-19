package com.devvalor.howyoudoing.app.ondevice

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceHistoryError
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceHistoryStatus
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceLinkedAccount
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceLoginPrompt
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.ondevice.OnDevicePairing
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceSignal
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceTelegram
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/**
 * One of hydbridge's linked-device clients (WhatsApp, Telegram, Signal) as each platform's bindings expose it.
 * Calls throw on failure; what a network doesn't support throws too.
 */
interface NativeLinkedClient {
    fun isLinked(): Boolean
    /** Connects an existing login. */
    fun connect()
    /** Starts linking; what happens with the phone number depends on the network (see OnDeviceLinkedAccount). */
    fun startLinking(phoneNumber: String)
    fun submitCode(code: String): Unit = throw UnsupportedOperationException()
    fun submitPassword(password: String): Unit = throw UnsupportedOperationException()
    fun cancelLinking()
    fun disconnect()
    fun logout()
    fun sendText(chat: String, text: String): String
    fun sendReaction(chat: String, messageId: String, senderId: String, emoji: String)
    fun markRead(chat: String, messageId: String, senderId: String)
    fun setTyping(chat: String, isTyping: Boolean)
    /** Asks for messages older than the given one (newest page when [beforeMessageId] is empty). */
    fun requestOlder(chat: String, beforeMessageId: String, beforeIsOutgoing: Boolean, beforeTimestampMs: Long)
    fun sendMediaFile(chat: String, path: String, mimeType: String, fileName: String, caption: String, width: Long, height: Long): String
    /** Sends a recording as a voice note; [waveform] is comma-separated loudness values, 0–255. */
    fun sendVoice(chat: String, path: String, mimeType: String, durationMs: Long, waveform: String): String =
        throw UnsupportedOperationException("Voice messages aren't supported here yet")
    /** Downloads media from a message event's mediaRef and returns the file's path. */
    fun downloadMedia(chat: String, messageId: String, mediaRef: String): String
    /** Asks for these chats' pictures (comma-separated chat keys); they arrive as avatar events. */
    fun fetchAvatars(chats: String) = Unit
}

fun interface NativeLinkedClientFactory {
    fun create(dataDir: String, onEvent: (String) -> Unit): NativeLinkedClient
}

/** A linked-device network running in-process through hydbridge, shared by Android and iOS. */
abstract class NativeOnDeviceLinkedAccount(
    private val factory: NativeLinkedClientFactory?,
    private val dataDir: OnDeviceDataDirectory,
    private val scope: CoroutineScope
) : OnDeviceLinkedAccount {

    private val lock = Mutex()
    private var client: NativeLinkedClient? = null
    /** Retries connecting a linked account after a failure (e.g. no network yet after a reboot). */
    private var retryJob: Job? = null

    private val _state = MutableStateFlow<OnDeviceMessengerState>(OnDeviceMessengerState.NotLinked)
    override val state: StateFlow<OnDeviceMessengerState> = _state.asStateFlow()

    private val _pairing = MutableStateFlow<OnDevicePairing?>(null)
    override val pairing: StateFlow<OnDevicePairing?> = _pairing.asStateFlow()

    private val _events = MutableSharedFlow<String>(replay = EVENT_REPLAY, extraBufferCapacity = 256)
    override val events: Flow<String> = _events.asSharedFlow()

    /** False when the network's client isn't available (e.g. Telegram without API credentials in the build). */
    override val isSupported = factory != null

    private var accountName: String? = null

    init {
        scope.launch { connectIfLinked() }
    }

    private fun createClient(): NativeLinkedClient =
        client ?: factory!!.create(dataDir.path, ::onNativeEvent).also { client = it }

    override suspend fun connectIfLinked() {
        if (factory == null) return
        val failure = lock.withLock { connectLocked() }
        if (failure) scheduleRetry()
    }

    /** Connects a linked account; true if that failed. */
    private suspend fun connectLocked(): Boolean = withContext(Dispatchers.IO) {
        val current = runCatching { createClient() }.getOrNull()
        if (current == null || !current.isLinked()) return@withContext false
        _state.value = OnDeviceMessengerState.Connecting
        runCatching { current.connect() }.onFailure { failed(it) }.isFailure
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            var wait = RETRY_FIRST_MILLIS
            while (true) {
                delay(wait)
                if (_state.value !is OnDeviceMessengerState.Failed) return@launch
                log("Retrying the connection")
                if (!lock.withLock { connectLocked() }) return@launch
                wait = (wait * 2).coerceAtMost(RETRY_MAX_MILLIS)
            }
        }
    }

    override suspend fun startLinking(phoneNumber: String?): EmptyResult<DataError.Remote> = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                client?.cancelLinking()
                _pairing.value = null
                _events.resetReplayCache()
                val created = createClient()
                _state.value = OnDeviceMessengerState.Connecting
                created.startLinking(phoneNumber.orEmpty())
                Result.Success(Unit)
            } catch (e: Exception) {
                failed(e)
                Result.Failure(DataError.Remote.UNKNOWN)
            }
        }
    }

    override suspend fun submitLinkingInput(value: String): EmptyResult<DataError.Remote> {
        val prompt = _pairing.value?.prompt ?: return Result.Failure(DataError.Remote.BAD_REQUEST)
        val current = client ?: return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        return withContext(Dispatchers.IO) {
            try {
                when (prompt.kind) {
                    OnDeviceLoginPrompt.Kind.CODE -> current.submitCode(value)
                    OnDeviceLoginPrompt.Kind.PASSWORD -> current.submitPassword(value)
                }
                Result.Success(Unit)
            } catch (e: Exception) {
                failed(e)
                Result.Failure(DataError.Remote.UNKNOWN)
            }
        }
    }

    override suspend fun cancelLinking() {
        lock.withLock {
            withContext(Dispatchers.IO) { runCatching { client?.cancelLinking() } }
            _pairing.value = null
            if (_state.value !is OnDeviceMessengerState.Connected) _state.value = OnDeviceMessengerState.NotLinked
        }
    }

    override suspend fun unlink() {
        retryJob?.cancel()
        lock.withLock {
            withContext(Dispatchers.IO) { runCatching { client?.logout() } }
            client = null
            dataDir.clear()
            _events.resetReplayCache()
            _pairing.value = null
            accountName = null
            _state.value = OnDeviceMessengerState.NotLinked
        }
    }

    // These networks link by pairing or prompts, not with web cookies
    override suspend fun link(cookies: Map<String, String>): EmptyResult<DataError.Remote> = Result.Failure(DataError.Remote.BAD_REQUEST)

    override suspend fun sendText(threadKey: String, text: String, isEncrypted: Boolean, isGroup: Boolean): Result<String, DataError.Remote> =
        native("Sending a message") { sendText(threadKey, text) }

    override suspend fun sendAttachment(
        threadKey: String, bytes: ByteArray, fileName: String, mimeType: String, width: Int?, height: Int?, caption: String?,
        isEncrypted: Boolean, isGroup: Boolean, voiceDurationMillis: Long?, waveform: List<Int>?
    ): Result<String, DataError.Remote> = native("Sending an attachment") {
        val path = dataDir.writeTemp(bytes)
        if (voiceDurationMillis != null) {
            sendVoice(threadKey, path, mimeType, voiceDurationMillis, waveform.orEmpty().joinToString(","))
        } else {
            sendMediaFile(threadKey, path, mimeType, fileName, caption.orEmpty(), (width ?: 0).toLong(), (height ?: 0).toLong())
        }
    }

    override suspend fun downloadMedia(threadKey: String, messageId: String, mediaRef: String): Result<ByteArray, DataError.Remote> =
        native("Downloading media") {
            val path = downloadMedia(threadKey, messageId, mediaRef)
            dataDir.read(path) ?: throw IllegalStateException("Downloaded media is missing")
        }

    override suspend fun readMediaFile(path: String): ByteArray? = withContext(Dispatchers.IO) { dataDir.read(path) }

    override suspend fun fetchAvatars(threadKeys: List<String>) {
        if (threadKeys.isNotEmpty()) native("Fetching pictures") { fetchAvatars(threadKeys.joinToString(",")) }
    }

    override suspend fun sendReaction(
        threadKey: String, messageId: String, emoji: String, isEncrypted: Boolean, isGroup: Boolean, messageSenderId: String
    ): EmptyResult<DataError.Remote> = native("Sending a reaction") { sendReaction(threadKey, messageId, messageSenderId, emoji) }

    override suspend fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String): EmptyResult<DataError.Remote> =
        native("Marking read") { markRead(threadKey, lastMessageId, senderId) }

    override suspend fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean): EmptyResult<DataError.Remote> =
        native("Sending typing") { setTyping(threadKey, isTyping) }

    override suspend fun requestOlder(
        threadKey: String, beforeTimestampMs: Long, beforeMessageId: String, beforeMessageIsOutgoing: Boolean
    ): EmptyResult<DataError.Remote> = native("Requesting history") {
        requestOlder(threadKey, beforeMessageId, beforeMessageIsOutgoing, beforeTimestampMs)
    }

    override suspend fun historyStatus(): Result<OnDeviceHistoryStatus, DataError.Remote> =
        Result.Success(OnDeviceHistoryStatus(isRestored = false, isBackupAvailable = false))

    override suspend fun restoreHistory(pin: String): EmptyResult<OnDeviceHistoryError> = Result.Failure(OnDeviceHistoryError.NoBackup)

    override suspend fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    private suspend fun <T> native(description: String, block: NativeLinkedClient.() -> T): Result<T, DataError.Remote> {
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
        _state.value = OnDeviceMessengerState.Failed(e.message?.replaceFirstChar { it.uppercase() } ?: "Couldn't connect")
    }

    private fun onNativeEvent(json: String) {
        val event = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        fun str(key: String) = event[key]?.jsonPrimitive?.contentOrNull
        when (str("type")) {
            "pairing" -> _pairing.value = OnDevicePairing(qrCode = str("qr"), pairingCode = str("code"))
            "login" -> {
                val kind = if (str("step") == "password") OnDeviceLoginPrompt.Kind.PASSWORD else OnDeviceLoginPrompt.Kind.CODE
                val detail = if (kind == OnDeviceLoginPrompt.Kind.PASSWORD) str("hint") else str("codeType")
                _pairing.value = OnDevicePairing(prompt = OnDeviceLoginPrompt(kind, detail?.ifBlank { null }, isRetry = str("error") == "invalid"))
                if (_state.value !is OnDeviceMessengerState.Connected) _state.value = OnDeviceMessengerState.Connecting
            }
            "debug" -> log("debug $json")
            "self" -> {
                _pairing.value = null
                str("name")?.takeIf { it.isNotBlank() }?.let { accountName = it }
                _state.value = OnDeviceMessengerState.Connected(accountName)
            }
            "state" -> {
                log("state ${str("state")} ${str("error").orEmpty()}")
                when (str("state")) {
                    "reconnecting" -> if (_state.value is OnDeviceMessengerState.Connected) _state.value = OnDeviceMessengerState.Connecting
                    "connected" -> if (_state.value == OnDeviceMessengerState.Connecting && _pairing.value == null) {
                        _state.value = OnDeviceMessengerState.Connected(accountName)
                    }
                    // Telegram connected without a login, and nobody is logging in
                    "needs_login" -> if (_pairing.value == null && _state.value !is OnDeviceMessengerState.Failed) {
                        _state.value = OnDeviceMessengerState.NotLinked
                    }
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
                        accountName = null
                        dataDir.clear()
                    }
                }
            }
        }
        _events.tryEmit(json)
    }

    private fun log(message: String) = println("OnDevice${network.name}: $message")

    private companion object {
        const val EVENT_REPLAY = 5_000
        const val RETRY_FIRST_MILLIS = 5_000L
        const val RETRY_MAX_MILLIS = 120_000L
    }
}

class NativeOnDeviceWhatsApp(factory: NativeLinkedClientFactory?, dataDir: OnDeviceDataDirectory, scope: CoroutineScope) :
    NativeOnDeviceLinkedAccount(factory, dataDir, scope), OnDeviceWhatsApp {
    override val network: ChatNetwork get() = ChatNetwork.WHATSAPP
}

class NativeOnDeviceTelegram(factory: NativeLinkedClientFactory?, dataDir: OnDeviceDataDirectory, scope: CoroutineScope) :
    NativeOnDeviceLinkedAccount(factory, dataDir, scope), OnDeviceTelegram {
    override val network: ChatNetwork get() = ChatNetwork.TELEGRAM
}

class NativeOnDeviceSignal(factory: NativeLinkedClientFactory?, dataDir: OnDeviceDataDirectory, scope: CoroutineScope) :
    NativeOnDeviceLinkedAccount(factory, dataDir, scope), OnDeviceSignal {
    override val network: ChatNetwork get() = ChatNetwork.SIGNAL
}
