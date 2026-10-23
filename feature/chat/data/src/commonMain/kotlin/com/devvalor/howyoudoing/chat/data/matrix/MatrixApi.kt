package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.data.matrix.dto.MessagesResponseDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.SendEventResponseDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.SyncResponseDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.ContentUriDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.TurnServerDto
import com.devvalor.howyoudoing.core.data.networking.safeCall
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    /** Sends any message-like event, e.g. `m.room.message` or `m.reaction`. */
    suspend fun sendEvent(
        session: AuthInfo,
        roomId: String,
        type: String,
        transactionId: String,
        content: JsonObject
    ): Result<SendEventResponseDto, DataError.Remote> = safeCall {
        httpClient.put {
            endpoint(session, "rooms", roomId, "send", type, transactionId)
            setBody(content)
        }
    }

    suspend fun redact(
        session: AuthInfo,
        roomId: String,
        eventId: String,
        transactionId: String
    ): Result<SendEventResponseDto, DataError.Remote> = safeCall {
        httpClient.put {
            endpoint(session, "rooms", roomId, "redact", eventId, transactionId)
            setBody(JsonObject(emptyMap()))
        }
    }

    suspend fun setTyping(
        session: AuthInfo,
        roomId: String,
        isTyping: Boolean,
        timeoutMillis: Long
    ): Result<JsonObject, DataError.Remote> = safeCall {
        httpClient.put {
            endpoint(session, "rooms", roomId, "typing", session.userId)
            setBody(buildJsonObject {
                put("typing", isTyping)
                if (isTyping) put("timeout", timeoutMillis)
            })
        }
    }

    suspend fun upload(
        session: AuthInfo,
        bytes: ByteArray,
        contentType: String,
        fileName: String
    ): Result<ContentUriDto, DataError.Remote> = safeCall {
        httpClient.post {
            url {
                takeFrom(session.homeserverUrl)
                appendPathSegments("_matrix", "media", "v3", "upload")
                parameters.append("filename", fileName)
            }
            bearerAuth(session.accessToken)
            contentType(ContentType.parse(contentType))
            setBody(bytes)
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

    /** TURN servers for VoIP calls (`/voip/turnServer`). */
    suspend fun turnServer(session: AuthInfo): Result<TurnServerDto, DataError.Remote> = safeCall {
        httpClient.get { endpoint(session, "voip", "turnServer") }
    }

    suspend fun leaveRoom(
        session: AuthInfo,
        roomId: String
    ): Result<JsonObject, DataError.Remote> = safeCall {
        httpClient.post {
            endpoint(session, "rooms", roomId, "leave")
            setBody(JsonObject(emptyMap()))
        }
    }

    suspend fun download(url: String, headers: Map<String, String>): ByteArray? = runCatching {
        val response = httpClient.get(url) { headers.forEach { (name, value) -> header(name, value) } }
        if (response.status.value in 200..299) response.body<ByteArray>() else null
    }.getOrNull()

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
