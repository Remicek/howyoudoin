package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.data.matrix.dto.MessagesResponseDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.SendEventResponseDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.SyncResponseDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.TextMessageContentDto
import com.devvalor.howyoudoing.core.data.networking.safeCall
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import kotlinx.serialization.json.JsonObject

/** Thin wrapper over the Matrix client-server API endpoints HowYouDoin needs. */
class MatrixApi(
    private val httpClient: HttpClient
) {

    suspend fun sync(
        session: AuthInfo,
        since: String?,
        timeoutMillis: Long
    ): Result<SyncResponseDto, DataError.Remote> = safeCall {
        httpClient.get {
            endpoint(session, "sync")
            parameter("timeout", timeoutMillis)
            parameter("filter", SYNC_FILTER)
            since?.let { parameter("since", it) }
        }
    }

    suspend fun messages(
        session: AuthInfo,
        roomId: String,
        from: String,
        limit: Int = 30
    ): Result<MessagesResponseDto, DataError.Remote> = safeCall {
        httpClient.get {
            endpoint(session, "rooms", roomId, "messages")
            parameter("from", from)
            parameter("dir", "b")
            parameter("limit", limit)
            parameter("filter", """{"lazy_load_members":true}""")
        }
    }

    suspend fun sendText(
        session: AuthInfo,
        roomId: String,
        transactionId: String,
        body: String
    ): Result<SendEventResponseDto, DataError.Remote> = safeCall {
        httpClient.put {
            endpoint(session, "rooms", roomId, "send", "m.room.message", transactionId)
            setBody(TextMessageContentDto(body = body))
        }
    }

    suspend fun sendReadReceipt(
        session: AuthInfo,
        roomId: String,
        eventId: String
    ): Result<JsonObject, DataError.Remote> = safeCall {
        httpClient.post {
            endpoint(session, "rooms", roomId, "receipt", "m.read", eventId)
            setBody(JsonObject(emptyMap()))
        }
    }

    suspend fun joinRoom(
        session: AuthInfo,
        roomId: String
    ): Result<JsonObject, DataError.Remote> = safeCall {
        httpClient.post {
            endpoint(session, "join", roomId)
            setBody(JsonObject(emptyMap()))
        }
    }

    private fun HttpRequestBuilder.endpoint(session: AuthInfo, vararg path: String) {
        url {
            takeFrom(session.homeserverUrl)
            appendPathSegments("_matrix", "client", "v3", *path)
        }
        bearerAuth(session.accessToken)
    }

    private companion object {
        const val SYNC_FILTER =
            """{"room":{"timeline":{"limit":30},"state":{"lazy_load_members":true}},"presence":{"not_types":["*"]}}"""
    }
}
