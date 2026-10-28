// Calls into the Go library (hydbridge) fail with plain Exceptions carrying Go's error text, so they're caught as such
@file:Suppress("TooGenericExceptionCaught")

package com.devvalor.howyoudoing.app.ondevice

import com.devvalor.howyoudoing.core.domain.media.Attachment
import com.devvalor.howyoudoing.core.domain.media.MessageRef
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceHistoryError
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceInstagram
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceHistoryStatus
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceSlack
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Go hydbridge client (bridge-mobile/), as each platform's generated bindings expose it. Calls throw on failure.
 */
// gobind passes only plain values (strings, numbers, byte arrays), so calls take every field as its own parameter
@Suppress("LongParameterList")
interface NativeMessengerClient {
    fun connect()
    fun disconnect()
    fun sendText(threadKey: String, text: String): String
    fun sendEncryptedText(threadKey: String, isGroup: Boolean, text: String): String

    /** Sends text replying to a message (regular or encrypted chat). */
    fun sendReply(
        threadKey: String,
        isEncrypted: Boolean,
        isGroup: Boolean,
        text: String,
        replyToId: String,
        replySenderId: String
    ): String =
        if (isEncrypted) sendEncryptedText(threadKey, isGroup, text) else sendText(threadKey, text)

    fun editMessage(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, messageId: String, text: String): Unit =
        throw UnsupportedOperationException("Editing isn't supported here")

    fun unsend(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, messageId: String): Unit =
        throw UnsupportedOperationException("Deleting isn't supported here")

    fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String)
    fun historyStatus(): String
    fun restoreHistory(pin: String)
    fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long, count: Long)
    fun sendReaction(threadKey: String, messageId: String, emoji: String)
    fun sendEncryptedReaction(
        threadKey: String,
        isGroup: Boolean,
        messageId: String,
        messageSenderId: String,
        emoji: String
    )

    fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String)
    fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean)

    /** Looks these contacts up again for fresh profile pictures (comma-separated IDs). */
    fun fetchAvatars(contactIds: String) = Unit
    fun sendMediaFile(
        threadKey: String, isEncrypted: Boolean, isGroup: Boolean, path: String, mimeType: String, fileName: String,
        caption: String, width: Long, height: Long
    ): String = throw UnsupportedOperationException("Attachments aren't supported here yet")

    /** Sends a recording as a voice note; [waveform] is comma-separated loudness values, 0–255. */
    fun sendVoice(
        threadKey: String,
        isEncrypted: Boolean,
        isGroup: Boolean,
        path: String,
        mimeType: String,
        durationMs: Long,
        waveform: String
    ): String = throw UnsupportedOperationException("Voice messages aren't supported here yet")
}

fun interface NativeMessengerFactory {
    fun create(cookiesJson: String, dataDir: String, onEvent: (String) -> Unit): NativeMessengerClient
}

/** Where the session cookies are kept, encrypted at rest by the platform. */
interface OnDeviceSecretStore {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/** Private directory for the encrypted-chat keys and history device. */
interface OnDeviceDataDirectory {
    val path: String
    fun clear()

    /** Writes [bytes] to a new file inside the directory and returns its path. */
    fun writeTemp(bytes: ByteArray): String

    /** Reads a file inside the directory; null for paths outside it or missing files. */
    fun read(path: String): ByteArray?
}

/**
 * Messenger (or Instagram, see [NativeOnDeviceInstagram]) running in-process through hydbridge, shared by Android and
 * iOS.
 */
open class NativeOnDeviceMessenger(
    private val factory: NativeMessengerFactory,
    private val secrets: OnDeviceSecretStore,
    private val dataDir: OnDeviceDataDirectory,
    private val scope: CoroutineScope
) : OnDeviceMessenger {

    private val lock = Mutex()
    private var client: NativeMessengerClient? = null
    private var retryJob: Job? = null

    private val _state = MutableStateFlow<OnDeviceMessengerState>(OnDeviceMessengerState.NotLinked)
    override val state: StateFlow<OnDeviceMessengerState> = _state.asStateFlow()

    // The inbox arrives as one burst on connect; replay it so screens opened later still get it
    private val _events = MutableSharedFlow<String>(replay = EVENT_REPLAY, extraBufferCapacity = 256)
    override val events: Flow<String> = _events.asSharedFlow()

    override val isSupported = true

    init {
        scope.launch { connectIfLinked() }
    }

    override suspend fun link(cookies: Map<String, String>): EmptyResult<DataError.Remote> {
        val json = JsonObject(cookies.mapValues { JsonPrimitive(it.value) }).toString()
        val result = connect(json)
        if (result is Result.Success) {
            secrets.write(json)
        } else if (secrets.read() == null) {
            // A login that never worked isn't a broken connection: the caller shows the error, and the network
            // stays not connected
            _state.value = OnDeviceMessengerState.NotLinked
        }
        return result
    }

    override suspend fun connectIfLinked() {
        val saved = secrets.read() ?: return
        if (connect(saved) is Result.Failure) scheduleRetry()
    }

    /**
     * A saved login that failed to connect (e.g. no network yet after a reboot) is tried again, waiting longer each
     * time.
     */
    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            var wait = RETRY_FIRST_MILLIS
            while (true) {
                delay(wait)
                if (_state.value !is OnDeviceMessengerState.Failed) return@launch
                val saved = secrets.read() ?: return@launch
                log("Retrying the connection")
                if (connect(saved) is Result.Success) return@launch
                wait = (wait * 2).coerceAtMost(RETRY_MAX_MILLIS)
            }
        }
    }

    private suspend fun connect(cookiesJson: String): EmptyResult<DataError.Remote> = lock.withLock {
        withContext(Dispatchers.IO) {
            client?.disconnect()
            client = null
            _events.resetReplayCache()
            _state.value = OnDeviceMessengerState.Connecting
            try {
                val created = factory.create(cookiesJson, dataDir.path, ::onNativeEvent)
                created.connect()
                client = created
                Result.Success(Unit)
            } catch (e: Exception) {
                log("Connecting failed: ${e.message}")
                _state.value = OnDeviceMessengerState.Failed(e.message ?: "Couldn't connect")
                Result.Failure(DataError.Remote.UNKNOWN)
            }
        }
    }

    override suspend fun sendText(
        threadKey: String,
        text: String,
        isEncrypted: Boolean,
        isGroup: Boolean
    ): Result<String, DataError.Remote> =
        native("Sending a message") {
            if (isEncrypted) sendEncryptedText(threadKey, isGroup, text) else sendText(
                threadKey,
                text
            )
        }

    override suspend fun sendReply(
        threadKey: String,
        text: String,
        isEncrypted: Boolean,
        isGroup: Boolean,
        replyTo: MessageRef
    ): Result<String, DataError.Remote> =
        native("Sending a reply") { sendReply(threadKey, isEncrypted, isGroup, text, replyTo.id, replyTo.senderId) }

    override suspend fun editMessage(
        threadKey: String,
        messageId: String,
        text: String,
        isEncrypted: Boolean,
        isGroup: Boolean
    ): EmptyResult<DataError.Remote> =
        native("Editing a message") { editMessage(threadKey, isEncrypted, isGroup, messageId, text) }

    override suspend fun unsend(
        threadKey: String,
        messageId: String,
        isEncrypted: Boolean,
        isGroup: Boolean
    ): EmptyResult<DataError.Remote> =
        native("Deleting a message") { unsend(threadKey, isEncrypted, isGroup, messageId) }

    override suspend fun sendAttachment(
        threadKey: String,
        attachment: Attachment,
        caption: String?,
        isEncrypted: Boolean,
        isGroup: Boolean
    ): Result<String, DataError.Remote> = native("Sending an attachment") {
        val path = dataDir.writeTemp(attachment.bytes)
        val voice = attachment.voice
        if (voice != null) {
            sendVoice(
                threadKey,
                isEncrypted,
                isGroup,
                path,
                attachment.mimeType,
                voice.durationMillis,
                voice.waveform.orEmpty().joinToString(",")
            )
        } else {
            sendMediaFile(
                threadKey,
                isEncrypted,
                isGroup,
                path,
                attachment.mimeType,
                attachment.fileName,
                caption.orEmpty(),
                (attachment.width ?: 0).toLong(),
                (attachment.height ?: 0).toLong()
            )
        }
    }

    override suspend fun readMediaFile(path: String): ByteArray? = withContext(Dispatchers.IO) { dataDir.read(path) }

    override suspend fun fetchAvatars(threadKeys: List<String>) {
        if (threadKeys.isNotEmpty()) native("Refreshing pictures") { fetchAvatars(threadKeys.joinToString(",")) }
    }

    override suspend fun historyStatus(): Result<OnDeviceHistoryStatus, DataError.Remote> =
        native("Checking history") {
            val status = Json.parseToJsonElement(historyStatus()).jsonObject
            OnDeviceHistoryStatus(
                isRestored = status["restored"]?.jsonPrimitive?.booleanOrNull == true,
                isBackupAvailable = status["backupAvailable"]?.jsonPrimitive?.booleanOrNull == true
            )
        }

    override suspend fun restoreHistory(pin: String): EmptyResult<OnDeviceHistoryError> {
        val current = client ?: return Result.Failure(OnDeviceHistoryError.Other("Messenger isn't connected"))
        return withContext(Dispatchers.IO) {
            try {
                current.restoreHistory(pin)
                Result.Success(Unit)
            } catch (e: Exception) {
                // hydbridge reports wrong_pin:<attempts>, pin_limit or no_backup
                val code = e.message.orEmpty().let { message ->
                    CODES.firstOrNull { message.contains(it) }
                        ?.let { found -> message.substring(message.indexOf(found)) } ?: message
                }
                Result.Failure(
                    when {
                        code.startsWith("wrong_pin:") ->
                            OnDeviceHistoryError.WrongPin(
                                code.substringAfter(':').takeWhile(Char::isDigit).toIntOrNull() ?: 0
                            )

                        code.startsWith("pin_limit") -> OnDeviceHistoryError.NoAttemptsLeft
                        code.startsWith("no_backup") -> OnDeviceHistoryError.NoBackup
                        else -> OnDeviceHistoryError.Other(code)
                    }
                )
            }
        }
    }

    override suspend fun fetchEncryptedHistory(
        threadKey: String,
        isGroup: Boolean,
        beforeTimestampMs: Long
    ): EmptyResult<DataError.Remote> =
        native("Fetching encrypted history") {
            fetchEncryptedHistory(
                threadKey,
                isGroup,
                beforeTimestampMs,
                HISTORY_PAGE_SIZE
            )
        }

    override suspend fun requestOlder(
        threadKey: String, beforeTimestampMs: Long, beforeMessageId: String, beforeMessageIsOutgoing: Boolean
    ): EmptyResult<DataError.Remote> =
        native("Requesting older messages") { requestOlder(threadKey, beforeTimestampMs, beforeMessageId) }

    override suspend fun sendReaction(
        threadKey: String,
        message: MessageRef,
        emoji: String,
        isEncrypted: Boolean,
        isGroup: Boolean
    ): EmptyResult<DataError.Remote> = native("Sending a reaction") {
        if (isEncrypted) {
            sendEncryptedReaction(threadKey, isGroup, message.id, message.senderId, emoji)
        } else {
            sendReaction(threadKey, message.id, emoji)
        }
    }

    override suspend fun markRead(
        threadKey: String,
        isEncrypted: Boolean,
        isGroup: Boolean,
        lastMessageId: String,
        senderId: String
    ): EmptyResult<DataError.Remote> =
        native("Marking read") { markRead(threadKey, isEncrypted, isGroup, lastMessageId, senderId) }

    override suspend fun setTyping(
        threadKey: String,
        isEncrypted: Boolean,
        isGroup: Boolean,
        isTyping: Boolean
    ): EmptyResult<DataError.Remote> =
        native("Sending typing") { setTyping(threadKey, isEncrypted, isGroup, isTyping) }

    override suspend fun unlink() {
        retryJob?.cancel()
        lock.withLock {
            withContext(Dispatchers.IO) { client?.disconnect() }
            client = null
            secrets.clear()
            dataDir.clear()
            _events.resetReplayCache()
            _state.value = OnDeviceMessengerState.NotLinked
        }
    }

    /** Runs a native call off the main thread; exceptions become failures. */
    private suspend fun <T> native(
        description: String,
        block: NativeMessengerClient.() -> T
    ): Result<T, DataError.Remote> {
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

    private fun onNativeEvent(json: String) {
        val event = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "self" -> _state.value = OnDeviceMessengerState.Connected(event["name"]?.jsonPrimitive?.contentOrNull)
            "state" -> when (
                event["state"]?.jsonPrimitive?.contentOrNull
                    .also { log("state $it ${event["error"]?.jsonPrimitive?.contentOrNull.orEmpty()}") }
            ) {
                "error" -> _state.value =
                    OnDeviceMessengerState.Failed(event["error"]?.jsonPrimitive?.contentOrNull ?: "Disconnected")

                "reconnecting" -> _state.value = OnDeviceMessengerState.Connecting
            }
        }
        _events.tryEmit(json)
    }

    private fun log(message: String) = println("OnDeviceMessenger: $message")

    private companion object {
        const val EVENT_REPLAY = 5_000
        const val HISTORY_PAGE_SIZE = 50L
        const val RETRY_FIRST_MILLIS = 5_000L
        const val RETRY_MAX_MILLIS = 120_000L
        val CODES = listOf("wrong_pin:", "pin_limit", "no_backup")
    }
}

/** Instagram DMs through hydbridge's instameow wrapper; same cookie login and events as Messenger. */
class NativeOnDeviceInstagram(
    factory: NativeMessengerFactory,
    secrets: OnDeviceSecretStore,
    dataDir: OnDeviceDataDirectory,
    scope: CoroutineScope
) : NativeOnDeviceMessenger(factory, secrets, dataDir, scope), OnDeviceInstagram {
    override val network: ChatNetwork get() = ChatNetwork.INSTAGRAM
}


/** A Slack workspace through hydbridge's Slack client; the "cookies" are the web client's token and d cookie. */
class NativeOnDeviceSlack(
    factory: NativeMessengerFactory,
    secrets: OnDeviceSecretStore,
    dataDir: OnDeviceDataDirectory,
    scope: CoroutineScope
) : NativeOnDeviceMessenger(factory, secrets, dataDir, scope), OnDeviceSlack {
    override val network: ChatNetwork get() = ChatNetwork.SLACK
}

/**
 * A network logged in from its web page or with a password (Bluesky, LinkedIn, Discord, Google Voice), through
 * hydbridge's shared ChatClient; the "cookies" are whatever that network's login needs.
 */
class NativeOnDeviceWebAccount(
    override val network: ChatNetwork,
    factory: NativeMessengerFactory,
    secrets: OnDeviceSecretStore,
    dataDir: OnDeviceDataDirectory,
    scope: CoroutineScope
) : NativeOnDeviceMessenger(factory, secrets, dataDir, scope)
