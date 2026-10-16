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

    /** Sends text to a thread; returns the offline threading ID the echoed message will carry. */
    suspend fun sendText(threadKey: String, text: String): Result<String, DataError.Remote>

    /** Asks for messages older than the given one; they arrive as message events plus a range event. */
    suspend fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String): EmptyResult<DataError.Remote>

    suspend fun unlink()
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
    override suspend fun sendText(threadKey: String, text: String): Result<String, DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)
    override suspend fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.UNKNOWN)
    override suspend fun unlink() = Unit
}
