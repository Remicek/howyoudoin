package com.relay.auth.data

import com.relay.auth.data.dto.IdentifierDto
import com.relay.auth.data.dto.LoginRequestDto
import com.relay.auth.data.dto.LoginResponseDto
import com.relay.core.data.networking.safeCall
import com.relay.core.domain.auth.AuthInfo
import com.relay.core.domain.auth.AuthService
import com.relay.core.domain.auth.SessionStorage
import com.relay.core.domain.util.DataError
import com.relay.core.domain.util.EmptyResult
import com.relay.core.domain.util.Result
import com.relay.core.domain.util.map
import com.relay.core.domain.util.onSuccess
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.json.JsonObject

class MatrixAuthService(
    private val httpClient: HttpClient,
    private val homeserverResolver: HomeserverResolver,
    private val sessionStorage: SessionStorage
) : AuthService {

    override suspend fun login(
        homeserver: String,
        username: String,
        password: String
    ): Result<AuthInfo, DataError.Remote> {
        val baseUrl = homeserverResolver.resolve(homeserver, username)
        return safeCall<LoginResponseDto> {
            httpClient.post("$baseUrl/_matrix/client/v3/login") {
                setBody(
                    LoginRequestDto(
                        identifier = IdentifierDto(user = username.trim()),
                        password = password
                    )
                )
            }
        }.map { response ->
            AuthInfo(
                homeserverUrl = response.wellKnown?.homeserver?.baseUrl?.trimEnd('/') ?: baseUrl,
                userId = response.userId,
                accessToken = response.accessToken,
                deviceId = response.deviceId
            )
        }.onSuccess { sessionStorage.set(it) }
    }

    override suspend fun logout(): EmptyResult<DataError.Remote> {
        val session = sessionStorage.get()
        if (session != null && !session.isDemo) {
            // Best effort: the local session is dropped even if the server call fails.
            safeCall<JsonObject> {
                httpClient.post("${session.homeserverUrl}/_matrix/client/v3/logout") {
                    bearerAuth(session.accessToken)
                    setBody(JsonObject(emptyMap()))
                }
            }
        }
        sessionStorage.set(null)
        return Result.Success(Unit)
    }
}
