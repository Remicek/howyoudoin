// Calls into the Go library (hydbridge) fail with plain Exceptions carrying Go's error text, so they're caught as such
@file:Suppress("TooGenericExceptionCaught")

package com.devvalor.howyoudoing.app.ondevice

import com.devvalor.howyoudoing.core.domain.util.AppLog
import com.devvalor.howyoudoing.core.domain.media.Attachment
import com.devvalor.howyoudoing.core.domain.media.MessageRef
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.CaptchaChallenge
import com.devvalor.howyoudoing.core.domain.ondevice.CaptchaSolver
import com.devvalor.howyoudoing.core.domain.ondevice.FoundPerson
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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

    /** The token of the CAPTCHA the last call failed for (see [captchaChallenge]); the call is then made again. */
    fun answerCaptcha(token: String): Unit = throw UnsupportedOperationException("No CAPTCHA here")
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

    /** People found on the network, as a JSON array of {"id", "name", "handle", "avatarUrl"}. */
    fun searchPeople(query: String): String = throw UnsupportedOperationException("Searching isn't supported here")

    /** Opens the one-to-one chat with a person found by [searchPeople]; returns its thread key. */
    fun startChat(personId: String): String = throw UnsupportedOperationException("New chats aren't supported here")
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

    /**
     * Writes a video's preview picture beside the file at [mediaPath] (written by [writeTemp]), at
     * [thumbnailPathOf], where the bridge looks for it while sending. Platforms without it send videos without one.
     */
    fun writeThumbnail(mediaPath: String, jpeg: ByteArray) = Unit

    /** Removes what [writeThumbnail] wrote, once the bridge is done with it. */
    fun deleteThumbnail(mediaPath: String) = Unit

    /** Whether the empty file [name] is in the directory; platforms without it answer yes. */
    fun hasMarker(name: String): Boolean = true

    /** Puts the empty file [name] in the directory ([clear] removes it). */
    fun writeMarker(name: String) = Unit
}

/** Where the bridge looks for a video's preview picture: beside the video, see hydbridge's `takeVideoThumbnail`. */
fun thumbnailPathOf(mediaPath: String): String = "$mediaPath.thumb.jpg"

/** Calls [send] with the attachment's preview picture written beside [path], and removes the picture afterwards. */
internal inline fun <T> OnDeviceDataDirectory.withThumbnail(path: String, attachment: Attachment, send: () -> T): T {
    val thumbnail = attachment.thumbnailJpeg ?: return send()
    writeThumbnail(path, thumbnail)
    return try {
        send()
    } finally {
        deleteThumbnail(path)
    }
}

/**
 * Messenger (or Instagram, see [NativeOnDeviceInstagram]) running in-process through hydbridge, shared by Android and
 * iOS.
 */
open class NativeOnDeviceMessenger(
    private val factory: NativeMessengerFactory,
    private val secrets: OnDeviceSecretStore,
    private val dataDir: OnDeviceDataDirectory,
    private val scope: CoroutineScope,
    private val captchaSolver: CaptchaSolver? = null,
    override val slot: Int = 0
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

    /** The page the network last said the user has to act on, kept for the next failure. */
    @kotlin.concurrent.Volatile
    private var actionUrl: String? = null

    override fun sessionCookies(): Map<String, String> =
        secrets.read()?.let { saved ->
            runCatching { Json.parseToJsonElement(saved).jsonObject }.getOrNull()
                ?.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }?.toMap()
        }.orEmpty()

    override fun updateSessionCookies(cookies: Map<String, String>) {
        val saved = sessionCookies()
        // Only the login's own cookies, renewed; the website's others aren't part of it
        val renewed = cookies.filterKeys { it in saved }.filterValues { it.isNotBlank() }
        if (saved.isEmpty() || renewed.isEmpty()) return
        secrets.write(JsonObject((saved + renewed).mapValues { JsonPrimitive(it.value) }).toString())
    }

    private var lastLinkError: String? = null
    override val linkError: String? get() = lastLinkError

    override suspend fun link(cookies: Map<String, String>): EmptyResult<DataError.Remote> {
        val json = JsonObject(cookies.mapValues { JsonPrimitive(it.value) }).toString()
        val result = connect(json)
        lastLinkError = (_state.value as? OnDeviceMessengerState.Failed)?.message?.takeIf { result is Result.Failure }
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
            actionUrl = null
            _state.value = OnDeviceMessengerState.Connecting
            try {
                val created = factory.create(cookiesJson, dataDir.path, ::onNativeEvent)
                created.connect()
                client = created
                Result.Success(Unit)
            } catch (e: Exception) {
                log("Connecting failed: ${e.message}")
                _state.value = OnDeviceMessengerState.Failed(e.message ?: "Couldn't connect", actionUrl)
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
            dataDir.withThumbnail(path, attachment) {
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

    override suspend fun searchPeople(query: String): Result<List<FoundPerson>, DataError.Remote> =
        when (val found = native("Searching people") { searchPeople(query) }) {
            is Result.Failure -> found
            is Result.Success -> Result.Success(parsePeople(found.data))
        }

    override suspend fun startChatWith(personId: String): Result<String, DataError.Remote> =
        native("Starting a chat") { startChat(personId) }

    /**
     * Runs a native call off the main thread; exceptions become failures. A call the network wants a CAPTCHA for is
     * shown to the user and, once solved, made again.
     */
    private suspend fun <T> native(
        description: String,
        block: NativeMessengerClient.() -> T
    ): Result<T, DataError.Remote> {
        val current = client ?: return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        return withContext(Dispatchers.IO) {
            var isAnswered = false
            while (true) {
                try {
                    return@withContext Result.Success(current.block()).also { log("$description: ok") }
                } catch (e: Exception) {
                    val challenge = captchaChallenge(network, e.message).takeIf { !isAnswered }
                    val token = challenge?.let { captchaSolver?.solve(it) }
                    if (token == null) {
                        log("$description failed: ${e.message}")
                        val isCancelled = challenge != null && captchaSolver != null
                        return@withContext Result.Failure(
                            if (isCancelled) DataError.Remote.CANCELLED else remoteError(e.message)
                        )
                    }
                    log("$description: CAPTCHA solved, trying again")
                    runCatching { current.answerCaptcha(token) }
                    isAnswered = true
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result.Failure(DataError.Remote.UNKNOWN)
        }
    }

    /** The account's name, from the "self" event of the first connect; reconnects don't send it again. */
    private var accountName: String? = null
    private var workspace: String? = null

    /** The network refuses to send until the account is verified (Discord). */
    private var needsVerification = false

    private fun onNativeEvent(json: String) {
        val event = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "self" -> {
                accountName = event["name"]?.jsonPrimitive?.contentOrNull
                workspace = event["workspace"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                _state.value = OnDeviceMessengerState.Connected(accountName, needsVerification, workspace)
            }

            // Diagnostics from the network client (event and table names only, never content)
            "debug" -> log("debug $json")

            "account" -> {
                needsVerification = event["needsVerification"]?.jsonPrimitive?.booleanOrNull == true
                (_state.value as? OnDeviceMessengerState.Connected)?.let {
                    _state.value = it.copy(needsVerification = needsVerification)
                }
            }

            "state" -> when (
                event["state"]?.jsonPrimitive?.contentOrNull
                    .also { log("state $it ${event["error"]?.jsonPrimitive?.contentOrNull.orEmpty()}") }
            ) {
                "error" -> _state.value =
                    OnDeviceMessengerState.Failed(
                        event["error"]?.jsonPrimitive?.contentOrNull ?: "Disconnected",
                        actionUrl
                    )

                "reconnecting" -> _state.value = OnDeviceMessengerState.Connecting

                // Comes just before connecting fails; the failure carries it
                "action_required" -> actionUrl = event["url"]?.jsonPrimitive?.contentOrNull

                // Back after a dropped connection (the first connect says so with "self")
                "connected" -> if (_state.value == OnDeviceMessengerState.Connecting && client != null) {
                    _state.value = OnDeviceMessengerState.Connected(accountName, needsVerification, workspace)
                }
            }
        }
        _events.tryEmit(json)
    }

    private fun log(message: String) = AppLog.d { "OnDeviceMessenger: $message" }

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
    scope: CoroutineScope,
    slot: Int = 0
) : NativeOnDeviceMessenger(factory, secrets, dataDir, scope, slot = slot), OnDeviceInstagram {
    override val network: ChatNetwork get() = ChatNetwork.INSTAGRAM
}


/** A Slack workspace through hydbridge's Slack client; the "cookies" are the web client's token and d cookie. */
class NativeOnDeviceSlack(
    factory: NativeMessengerFactory,
    secrets: OnDeviceSecretStore,
    dataDir: OnDeviceDataDirectory,
    scope: CoroutineScope,
    slot: Int = 0
) : NativeOnDeviceMessenger(factory, secrets, dataDir, scope, slot = slot), OnDeviceSlack {
    override val network: ChatNetwork get() = ChatNetwork.SLACK
}

/**
 * A network logged in from its web page or with a password (Bluesky, LinkedIn, Discord, Google Voice), through
 * hydbridge's shared ChatClient; the "cookies" are whatever that network's login needs.
 */
@Suppress("LongParameterList") // The shared client's dependencies plus which network and account
class NativeOnDeviceWebAccount(
    override val network: ChatNetwork,
    factory: NativeMessengerFactory,
    secrets: OnDeviceSecretStore,
    dataDir: OnDeviceDataDirectory,
    scope: CoroutineScope,
    captchaSolver: CaptchaSolver,
    slot: Int = 0
) : NativeOnDeviceMessenger(factory, secrets, dataDir, scope, captchaSolver, slot)

/** What kind of failure a native error is, from what the network clients put in it. */
internal fun remoteError(message: String?): DataError.Remote {
    val text = message.orEmpty()
    fun has(vararg hints: String) = hints.any { text.contains(it, ignoreCase = true) }
    return when {
        has("i/o timeout", "deadline exceeded", "timed out") -> DataError.Remote.REQUEST_TIMEOUT
        has("dial tcp", "no such host", "network is unreachable", "connection refused", "connection reset") ->
            DataError.Remote.NO_INTERNET
        has("HTTP 401", "Unauthorized") -> DataError.Remote.UNAUTHORIZED
        // Discord's 50007 is "Cannot send messages to this user" (they only take DMs from friends)
        has("HTTP 403", "Forbidden", "50007") -> DataError.Remote.FORBIDDEN
        has("HTTP 429", "Too Many Requests", "rate limit") -> DataError.Remote.TOO_MANY_REQUESTS
        has("HTTP 5") -> DataError.Remote.SERVER_ERROR
        else -> DataError.Remote.UNKNOWN
    }
}

/** The CAPTCHA in a native error ("captcha_required:" and its JSON), or null for any other error. */
internal fun captchaChallenge(network: ChatNetwork, message: String?): CaptchaChallenge? {
    val json = message?.substringAfter("captcha_required:", missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }
        ?: return null
    val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    fun field(name: String) = obj[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val siteKey = field("siteKey")
    val pageUrl = field("pageUrl")
    if (siteKey == null || pageUrl == null) return null
    return CaptchaChallenge(
        network = network,
        service = field("service") ?: "hcaptcha",
        siteKey = siteKey,
        rqData = field("rqData"),
        pageUrl = pageUrl
    )
}

/** Reads SearchPeople's JSON array; anything that isn't one gives no one. */
internal fun parsePeople(json: String): List<FoundPerson> =
    runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull().orEmpty().mapNotNull { element ->
        val person = element as? JsonObject ?: return@mapNotNull null
        val id = person["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        FoundPerson(
            id = id,
            name = person["name"]?.jsonPrimitive?.contentOrNull ?: id,
            handle = person["handle"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            avatarUrl = person["avatarUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        )
    }
