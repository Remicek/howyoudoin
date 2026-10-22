package com.devvalor.howyoudoing.app.ondevice

import com.devvalor.howyoudoing.auth.data.HomeserverResolver
import com.devvalor.howyoudoing.auth.data.MatrixAuthService
import com.devvalor.howyoudoing.core.domain.auth.SessionStorage
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceHistoryError
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceHistoryStatus
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMatrix
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.domain.util.asEmptyResult
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Matrix account connected as a network. It only signs in and out: its rooms come from the second Matrix sync
 * engine on [sessionStorage] (see MatrixAccountChats), so the messaging calls of [OnDeviceMatrix] aren't used.
 */
class MatrixAccount(
    httpClient: HttpClient,
    private val sessionStorage: SessionStorage,
    scope: CoroutineScope
) : OnDeviceMatrix {

    private val auth = MatrixAuthService(httpClient, HomeserverResolver(httpClient), sessionStorage)

    override val isSupported = true

    override val state: StateFlow<OnDeviceMessengerState> = sessionStorage.observeAuthInfo()
        .map { session -> if (session == null) OnDeviceMessengerState.NotLinked else OnDeviceMessengerState.Connected(session.userId) }
        .stateIn(scope, SharingStarted.Eagerly, OnDeviceMessengerState.NotLinked)

    override val events: Flow<String> = emptyFlow()

    override suspend fun link(cookies: Map<String, String>): EmptyResult<DataError.Remote> {
        val homeserver = cookies[HOMESERVER]?.trim().orEmpty().ifEmpty { DEFAULT_HOMESERVER }
        val username = cookies[USERNAME]?.trim().orEmpty()
        val password = cookies[PASSWORD].orEmpty()
        if (username.isEmpty() || password.isEmpty()) return Result.Failure(DataError.Remote.BAD_REQUEST)
        return auth.login(homeserver, username, password).asEmptyResult()
    }

    override suspend fun unlink() {
        auth.logout()
    }

    // Signing in is all there is to connecting
    override suspend fun connectIfLinked() = Unit

    override suspend fun sendText(threadKey: String, text: String, isEncrypted: Boolean, isGroup: Boolean): Result<String, DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    override suspend fun historyStatus(): Result<OnDeviceHistoryStatus, DataError.Remote> =
        Result.Success(OnDeviceHistoryStatus(isRestored = false, isBackupAvailable = false))

    override suspend fun restoreHistory(pin: String): EmptyResult<OnDeviceHistoryError> = Result.Failure(OnDeviceHistoryError.NoBackup)

    override suspend fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    override suspend fun requestOlder(
        threadKey: String, beforeTimestampMs: Long, beforeMessageId: String, beforeMessageIsOutgoing: Boolean
    ): EmptyResult<DataError.Remote> = Result.Failure(DataError.Remote.BAD_REQUEST)

    override suspend fun sendReaction(
        threadKey: String, messageId: String, emoji: String, isEncrypted: Boolean, isGroup: Boolean, messageSenderId: String
    ): EmptyResult<DataError.Remote> = Result.Failure(DataError.Remote.BAD_REQUEST)

    override suspend fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    override suspend fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    companion object {
        const val HOMESERVER = "homeserver"
        const val USERNAME = "username"
        const val PASSWORD = "password"
        const val DEFAULT_HOMESERVER = "matrix.org"
    }
}
