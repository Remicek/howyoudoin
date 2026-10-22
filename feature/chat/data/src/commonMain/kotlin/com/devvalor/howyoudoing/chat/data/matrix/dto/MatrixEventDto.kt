package com.devvalor.howyoudoing.chat.data.matrix.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Generic Matrix event, content is kept as raw JSON and read by the mappers. */
@Serializable
data class MatrixEventDto(
    val type: String,
    @SerialName("event_id") val eventId: String? = null,
    val sender: String? = null,
    @SerialName("origin_server_ts") val originServerTs: Long? = null,
    val content: JsonObject = JsonObject(emptyMap()),
    @SerialName("state_key") val stateKey: String? = null,
    val unsigned: JsonObject? = null
) {
    val stateCacheKey: String get() = stateCacheKey(type, stateKey.orEmpty())

    val transactionId: String?
        get() = unsigned?.get("transaction_id")?.jsonPrimitive?.contentOrNull

    companion object {
        fun stateCacheKey(type: String, stateKey: String = "") = "$type|$stateKey"
    }
}

fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull

fun JsonObject.obj(key: String): JsonObject? =
    runCatching { get(key)?.jsonObject }.getOrNull()

@Serializable
data class MessagesResponseDto(
    val chunk: List<MatrixEventDto> = emptyList(),
    val start: String? = null,
    val end: String? = null,
    val state: List<MatrixEventDto> = emptyList()
)

@Serializable
data class SendEventResponseDto(
    @SerialName("event_id") val eventId: String
)

@Serializable
data class ContentUriDto(
    @SerialName("content_uri") val contentUri: String
)

@Serializable
data class TurnServerDto(
    val uris: List<String> = emptyList(),
    val username: String? = null,
    val password: String? = null,
    val ttl: Long? = null
)
