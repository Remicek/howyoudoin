package com.devvalor.howyoudoing.core.domain.ondevice

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
    /** False where the native library isn't available (iOS for now). */
    val isSupported: Boolean
    val state: StateFlow<OnDeviceMessengerState>

    /**
     * Raw events from the native client as JSON objects with a "type" of self, thread, contact, participant,
     * message or state. Replays what was received since connecting, so late collectors see the whole inbox.
     */
    val events: Flow<String>

    /** Signs in with messenger.com session cookies (xs, c_user, datr) and keeps them for later launches. */
    suspend fun link(cookies: Map<String, String>): EmptyResult<DataError.Remote>

    /** Connects with saved cookies, if any. */
    suspend fun connectIfLinked()

    /**
     * Sends text to a thread; returns the ID the echoed message will carry (offline threading ID for regular
     * chats, message ID for encrypted ones).
     */
    suspend fun sendText(threadKey: String, text: String, isEncrypted: Boolean = false, isGroup: Boolean = false): Result<String, DataError.Remote>

    /** Messenger's PIN-protected backup of encrypted chats, as seen from this phone. */
    suspend fun historyStatus(): Result<OnDeviceHistoryStatus, DataError.Remote>

    /** Unlocks the backup with the 6-digit PIN so encrypted chats can load their history on this phone. */
    suspend fun restoreHistory(pin: String): EmptyResult<OnDeviceHistoryError>

    /** Loads decrypted history of an encrypted chat older than [beforeTimestampMs] (0 = newest). */
    suspend fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long): EmptyResult<DataError.Remote>

    /** Asks for messages older than the given one; they arrive as message events plus a range event. */
    suspend fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String): EmptyResult<DataError.Remote>

    /**
     * Reacts to a message; an empty [emoji] removes the reaction. Encrypted chats also need whether it's a group
     * and who sent the message.
     */
    suspend fun sendReaction(
        threadKey: String,
        messageId: String,
        emoji: String,
        isEncrypted: Boolean = false,
        isGroup: Boolean = false,
        messageSenderId: String = ""
    ): EmptyResult<DataError.Remote>

    /** Marks a chat read; encrypted chats need the newest message's ID and sender. */
    suspend fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String): EmptyResult<DataError.Remote>

    suspend fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean): EmptyResult<DataError.Remote>

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
    data class Connected(val accountName: String?) : OnDeviceMessengerState
    data class Failed(val message: String) : OnDeviceMessengerState
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
    override suspend fun sendText(threadKey: String, text: String, isEncrypted: Boolean, isGroup: Boolean): Result<String, DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)
    override suspend fun historyStatus(): Result<OnDeviceHistoryStatus, DataError.Remote> =
        Result.Success(OnDeviceHistoryStatus(isRestored = false, isBackupAvailable = false))
    override suspend fun restoreHistory(pin: String): EmptyResult<OnDeviceHistoryError> =
        Result.Failure(OnDeviceHistoryError.NoBackup)
    override suspend fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)
    override suspend fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)
    override suspend fun sendReaction(
        threadKey: String, messageId: String, emoji: String, isEncrypted: Boolean, isGroup: Boolean, messageSenderId: String
    ): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)
    override suspend fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)
    override suspend fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)
    override suspend fun unlink() = Unit
}
