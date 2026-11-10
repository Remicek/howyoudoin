package com.devvalor.howyoudoing.core.domain.ondevice

import com.devvalor.howyoudoing.core.domain.media.Attachment
import com.devvalor.howyoudoing.core.domain.media.MessageRef
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Experimental: Messenger connected straight from the phone (mautrix-meta's client compiled into the app),
 * instead of through a bridge on the server. Only regular chats for now; end-to-end encrypted ones need the
 * WhatsApp-protocol client as well.
 */
interface OnDeviceMessenger {
    /** Which network this client connects (Messenger, or WhatsApp through [OnDeviceWhatsApp]). */
    val network: ChatNetwork get() = ChatNetwork.MESSENGER

    /** Which of the network's accounts on the phone this is (0 for the first, see [AccountRef]). */
    val slot: Int get() = 0

    /** False where the native library isn't available. */
    val isSupported: Boolean
    val state: StateFlow<OnDeviceMessengerState>

    /**
     * Raw events from the native client as JSON objects with a "type" of self, thread, contact, participant,
     * message or state. Replays what was received since connecting, so late collectors see the whole inbox.
     */
    val events: Flow<String>

    /** Signs in with messenger.com session cookies (xs, c_user, datr) and keeps them for later launches. */
    suspend fun link(cookies: Map<String, String>): EmptyResult<DataError.Remote>

    /**
     * The key of the one-to-one chat with a contact, for writing to someone who isn't in the inbox yet; null where
     * the network needs more than the contact's ID to start a chat (not supported from the app yet).
     */
    fun directChatKey(contactId: String): String? = null

    /** Looks for people on the network by name or handle; fails where the network can't be searched from here. */
    suspend fun searchPeople(query: String): Result<List<FoundPerson>, DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    /** Opens the one-to-one chat with someone [searchPeople] found and returns its thread key. */
    suspend fun startChatWith(personId: String): Result<String, DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    /** Why the last [link] failed, in the network client's words (e.g. "Wrong Bluesky handle or app password"). */
    val linkError: String? get() = null

    /** Connects with saved cookies, if any. */
    suspend fun connectIfLinked()

    /** Drops the connection and starts a fresh one from the saved login (for one that's stuck reconnecting). */
    suspend fun reconnect() = connectIfLinked()

    /** The saved login's cookies, to open the network's website signed in as this account (see [AccountAction]). */
    fun sessionCookies(): Map<String, String> = emptyMap()

    /** Saves cookies the network's website renewed while the user acted there, over the saved login's. */
    fun updateSessionCookies(cookies: Map<String, String>) {}

    /**
     * Sends text to a thread; returns the ID the echoed message will carry (offline threading ID for regular
     * chats, message ID for encrypted ones).
     */
    suspend fun sendText(
        threadKey: String,
        text: String,
        isEncrypted: Boolean = false,
        isGroup: Boolean = false
    ): Result<String, DataError.Remote>

    /** Sends text replying to [replyTo] (some networks show its text in the quote); others send plain text. */
    suspend fun sendReply(
        threadKey: String,
        text: String,
        isEncrypted: Boolean,
        isGroup: Boolean,
        replyTo: MessageRef
    ): Result<String, DataError.Remote> = sendText(threadKey, text, isEncrypted, isGroup)

    /** Changes the text of one of our messages. */
    suspend fun editMessage(
        threadKey: String,
        messageId: String,
        text: String,
        isEncrypted: Boolean,
        isGroup: Boolean
    ): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    /** Deletes one of our messages for everyone. */
    suspend fun unsend(
        threadKey: String,
        messageId: String,
        isEncrypted: Boolean,
        isGroup: Boolean
    ): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    /**
     * Sends a photo, video, audio or file; returns the ID the echoed message carries. An attachment with a
     * [Attachment.voice] is a recorded voice message, sent as the network's voice note.
     */
    suspend fun sendAttachment(
        threadKey: String,
        attachment: Attachment,
        caption: String?,
        isEncrypted: Boolean,
        isGroup: Boolean
    ): Result<String, DataError.Remote> = Result.Failure(DataError.Remote.BAD_REQUEST)

    /** Downloads media that wasn't fetched with its message, from the reference the message event carried. */
    suspend fun downloadMedia(
        threadKey: String,
        messageId: String,
        mediaRef: String
    ): Result<ByteArray, DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    /** Asks for the pictures of these chats; they arrive as avatar events. Networks that send them anyway ignore it. */
    suspend fun fetchAvatars(threadKeys: List<String>) = Unit

    /** Reads a media file this client stored (a `file://` attachment URL's path); null if it isn't one of its files. */
    suspend fun readMediaFile(path: String): ByteArray? = null

    /** Messenger's PIN-protected backup of encrypted chats, as seen from this phone. */
    suspend fun historyStatus(): Result<OnDeviceHistoryStatus, DataError.Remote>

    /** Unlocks the backup with the 6-digit PIN so encrypted chats can load their history on this phone. */
    suspend fun restoreHistory(pin: String): EmptyResult<OnDeviceHistoryError>

    /** Loads decrypted history of an encrypted chat older than [beforeTimestampMs] (0 = newest). */
    suspend fun fetchEncryptedHistory(
        threadKey: String,
        isGroup: Boolean,
        beforeTimestampMs: Long
    ): EmptyResult<DataError.Remote>

    /** Asks for messages older than the given one; they arrive as message events plus a range event. */
    suspend fun requestOlder(
        threadKey: String,
        beforeTimestampMs: Long,
        beforeMessageId: String,
        beforeMessageIsOutgoing: Boolean = false
    ): EmptyResult<DataError.Remote>

    /**
     * Reacts to [message]; an empty [emoji] removes the reaction. Encrypted chats also need whether it's a group
     * and who sent the message.
     */
    suspend fun sendReaction(
        threadKey: String,
        message: MessageRef,
        emoji: String,
        isEncrypted: Boolean = false,
        isGroup: Boolean = false
    ): EmptyResult<DataError.Remote>

    /** Marks a chat read; encrypted chats need the newest message's ID and sender. */
    suspend fun markRead(
        threadKey: String,
        isEncrypted: Boolean,
        isGroup: Boolean,
        lastMessageId: String,
        senderId: String
    ): EmptyResult<DataError.Remote>

    suspend fun setTyping(
        threadKey: String,
        isEncrypted: Boolean,
        isGroup: Boolean,
        isTyping: Boolean
    ): EmptyResult<DataError.Remote>

    suspend fun unlink()
}

data class OnDeviceHistoryStatus(val isRestored: Boolean, val isBackupAvailable: Boolean) {
    val canRestore: Boolean get() = isBackupAvailable && !isRestored
}

sealed interface OnDeviceHistoryError : com.devvalor.howyoudoing.core.domain.util.Error {
    data class WrongPin(val attemptsRemaining: Int) : OnDeviceHistoryError
    data object NoAttemptsLeft : OnDeviceHistoryError
    data object NoBackup : OnDeviceHistoryError
    data class Other(val message: String) : OnDeviceHistoryError
}

sealed interface OnDeviceMessengerState {
    data object NotLinked : OnDeviceMessengerState
    data object Connecting : OnDeviceMessengerState
    /** [needsVerification]: the network won't send for this account until its email or phone is verified. */
    /**
     * [workspace]: for networks with workspaces (Slack), the one connected; [accountName] is then the user's name
     * in it.
     */
    data class Connected(
        val accountName: String?,
        val needsVerification: Boolean = false,
        val workspace: String? = null
    ) : OnDeviceMessengerState
    /**
     * [actionUrl]: a page on the network's website where the user has to act (e.g. accept its terms) before it
     * works.
     */
    data class Failed(val message: String, val actionUrl: String? = null) : OnDeviceMessengerState
}

/** Used where the native client isn't built in. */
object UnsupportedOnDeviceMessenger : OnDeviceMessenger {
    override val isSupported = false
    override val state: StateFlow<OnDeviceMessengerState> =
        kotlinx.coroutines.flow.MutableStateFlow(OnDeviceMessengerState.NotLinked)
    override val events: Flow<String> = kotlinx.coroutines.flow.emptyFlow()
    override suspend fun link(cookies: Map<String, String>): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)

    override suspend fun connectIfLinked() = Unit
    override suspend fun sendText(
        threadKey: String,
        text: String,
        isEncrypted: Boolean,
        isGroup: Boolean
    ): Result<String, DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)

    override suspend fun historyStatus(): Result<OnDeviceHistoryStatus, DataError.Remote> =
        Result.Success(OnDeviceHistoryStatus(isRestored = false, isBackupAvailable = false))

    override suspend fun restoreHistory(pin: String): EmptyResult<OnDeviceHistoryError> =
        Result.Failure(OnDeviceHistoryError.NoBackup)

    override suspend fun fetchEncryptedHistory(
        threadKey: String,
        isGroup: Boolean,
        beforeTimestampMs: Long
    ): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)

    override suspend fun requestOlder(
        threadKey: String, beforeTimestampMs: Long, beforeMessageId: String, beforeMessageIsOutgoing: Boolean
    ): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)

    override suspend fun sendReaction(
        threadKey: String,
        message: MessageRef,
        emoji: String,
        isEncrypted: Boolean,
        isGroup: Boolean
    ): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)

    override suspend fun markRead(
        threadKey: String,
        isEncrypted: Boolean,
        isGroup: Boolean,
        lastMessageId: String,
        senderId: String
    ): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)

    override suspend fun setTyping(
        threadKey: String,
        isEncrypted: Boolean,
        isGroup: Boolean,
        isTyping: Boolean
    ): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)

    override suspend fun unlink() = Unit
}

/**
 * An account linked to this phone by confirming on the user's other device (a QR code or pairing code) or by
 * answering prompts (a login code, a password): WhatsApp, Telegram and Signal.
 */
interface OnDeviceLinkedAccount : OnDeviceMessenger {
    /** The current QR code, pairing code or prompt while linking, else null. */
    val pairing: StateFlow<OnDevicePairing?>

    /**
     * Starts linking. WhatsApp: with a phone number (international format) a pairing code, otherwise QR codes.
     * Telegram: sends a login code to the phone number. Signal: QR codes (the phone number is ignored).
     */
    suspend fun startLinking(phoneNumber: String?): EmptyResult<DataError.Remote>

    /** Answers the current [OnDevicePairing.prompt]. */
    suspend fun submitLinkingInput(value: String): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    suspend fun cancelLinking()
}

/**
 * While linking: a [qrCode] to scan or a [pairingCode] to type on the other device, or a [prompt] for something
 * the user types here.
 */
data class OnDevicePairing(
    val qrCode: String? = null,
    val pairingCode: String? = null,
    val prompt: OnDeviceLoginPrompt? = null
)

data class OnDeviceLoginPrompt(
    val kind: Kind,
    /** Where a code was sent ("app", "sms", "call", "email"), or the password's hint. */
    val detail: String? = null,
    /** The previous answer was wrong. */
    val isRetry: Boolean = false
) {
    enum class Kind { CODE, PASSWORD }
}

/** Experimental: WhatsApp linked as a companion device of the user's phone, running in the app. */
interface OnDeviceWhatsApp : OnDeviceLinkedAccount {
    override val network: ChatNetwork get() = ChatNetwork.WHATSAPP
}

/** Experimental: Telegram logged in with a code sent to the user's Telegram app (or SMS), running in the app. */
interface OnDeviceTelegram : OnDeviceLinkedAccount {
    override val network: ChatNetwork get() = ChatNetwork.TELEGRAM
}

/** Experimental: Signal linked as a secondary device of the user's phone, running in the app. */
interface OnDeviceSignal : OnDeviceLinkedAccount {
    override val network: ChatNetwork get() = ChatNetwork.SIGNAL
}

/** Used where the native client isn't built in. */
object UnsupportedOnDeviceWhatsApp : OnDeviceWhatsApp, OnDeviceMessenger by UnsupportedOnDeviceMessenger {
    override val network: ChatNetwork get() = ChatNetwork.WHATSAPP
    override val pairing: StateFlow<OnDevicePairing?> = kotlinx.coroutines.flow.MutableStateFlow(null)
    override suspend fun startLinking(phoneNumber: String?): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)

    override suspend fun cancelLinking() = Unit
}

object UnsupportedOnDeviceTelegram : OnDeviceTelegram, OnDeviceMessenger by UnsupportedOnDeviceMessenger {
    override val network: ChatNetwork get() = ChatNetwork.TELEGRAM
    override val pairing: StateFlow<OnDevicePairing?> = kotlinx.coroutines.flow.MutableStateFlow(null)
    override suspend fun startLinking(phoneNumber: String?): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)

    override suspend fun cancelLinking() = Unit
}

object UnsupportedOnDeviceSignal : OnDeviceSignal, OnDeviceMessenger by UnsupportedOnDeviceMessenger {
    override val network: ChatNetwork get() = ChatNetwork.SIGNAL
    override val pairing: StateFlow<OnDevicePairing?> = kotlinx.coroutines.flow.MutableStateFlow(null)
    override suspend fun startLinking(phoneNumber: String?): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)

    override suspend fun cancelLinking() = Unit
}

/** Experimental: Instagram direct messages connected from the phone, logged in with instagram.com cookies. */
interface OnDeviceInstagram : OnDeviceMessenger {
    override val network: ChatNetwork get() = ChatNetwork.INSTAGRAM
}

object UnsupportedOnDeviceInstagram : OnDeviceInstagram, OnDeviceMessenger by UnsupportedOnDeviceMessenger {
    override val network: ChatNetwork get() = ChatNetwork.INSTAGRAM
}


/** Experimental: a Slack workspace connected from the phone, logged in with the web client's token and cookie. */
interface OnDeviceSlack : OnDeviceMessenger {
    override val network: ChatNetwork get() = ChatNetwork.SLACK
}

object UnsupportedOnDeviceSlack : OnDeviceSlack, OnDeviceMessenger by UnsupportedOnDeviceMessenger {
    override val network: ChatNetwork get() = ChatNetwork.SLACK
}

/** Used for a network whose native client isn't built in on this platform. */
class UnsupportedOnDeviceNetwork(override val network: ChatNetwork) : OnDeviceMessenger by UnsupportedOnDeviceMessenger

/**
 * A Matrix account (any homeserver) connected as one of the networks: its rooms join the inbox, also without the
 * app's own server. [link] takes "homeserver", "username" and "password".
 */
interface OnDeviceMatrix : OnDeviceMessenger {
    override val network: ChatNetwork get() = ChatNetwork.MATRIX
}

/** Someone a network search found: [handle] is how the network shows them (e.g. "@name"), when it has one. */
data class FoundPerson(
    val id: String,
    val name: String,
    val handle: String? = null,
    val avatarUrl: String? = null
)

/**
 * Which account this client connects. An extension, not a member: a client made by delegation ("by
 * UnsupportedOnDeviceMessenger") would otherwise take the delegate's.
 */
val OnDeviceMessenger.account: AccountRef get() = AccountRef(network, slot)
