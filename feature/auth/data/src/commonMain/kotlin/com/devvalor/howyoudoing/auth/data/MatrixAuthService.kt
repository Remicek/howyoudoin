package com.devvalor.howyoudoing.auth.data

import com.devvalor.howyoudoing.auth.data.dto.IdentifierDto
import com.devvalor.howyoudoing.auth.data.dto.LoginRequestDto
import com.devvalor.howyoudoing.auth.data.dto.LoginResponseDto
import com.devvalor.howyoudoing.core.data.networking.safeCall
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.auth.AuthService
import com.devvalor.howyoudoing.core.domain.auth.SessionStorage
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.domain.util.map
import com.devvalor.howyoudoing.core.domain.util.onSuccess
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
                // The login response can override the homeserver URL for all later authenticated calls; only honor
                // an https override, else keep the already-resolved base, so a server can't point the token at an
                // http or internal host.
                homeserverUrl = response.wellKnown?.homeserver?.baseUrl?.trimEnd('/')
                    ?.takeIf { it.startsWith("https://") }
                    ?: baseUrl,
                userId = response.userId,
                accessToken = response.accessToken,
                deviceId = response.deviceId
            )
        }.onSuccess { sessionStorage.set(it) }
    }

    override suspend fun logout(): EmptyResult<DataError.Remote> {
        val session = sessionStorage.get()
        if (session != null && session.hasServer) {
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
