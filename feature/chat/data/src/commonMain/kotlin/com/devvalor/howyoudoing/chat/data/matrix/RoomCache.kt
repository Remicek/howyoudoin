package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.data.matrix.dto.JoinedRoomDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.MatrixEventDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.EphemeralEventDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.MessagesResponseDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.obj
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** In-memory state of one joined room, built up from /sync and /messages responses. */
data class RoomCache(
    val roomId: String,
    val state: Map<String, MatrixEventDto> = emptyMap(),
    val timeline: List<MatrixEventDto> = emptyList(),
    val pending: List<PendingMessage> = emptyList(),
    val heroes: List<String> = emptyList(),
    val joinedMemberCount: Int = 0,
    val unreadCount: Int = 0,
    val prevBatch: String? = null,
    val hasMoreHistory: Boolean = true,
    /** Latest read receipt per user: user ID → event ID. */
    val receipts: Map<String, String> = emptyMap(),
    val typingUserIds: List<String> = emptyList()
) {
    fun stateEvent(type: String, stateKey: String = ""): MatrixEventDto? =
        state[MatrixEventDto.stateCacheKey(type, stateKey)]

    fun merge(dto: JoinedRoomDto): RoomCache {
        val timelineEvents = dto.timeline?.events.orEmpty()
        val newState = state.toMutableMap()
        (dto.state?.events.orEmpty() + timelineEvents.filter { it.stateKey != null })
            .forEach { newState[it.stateCacheKey] = it }

        val knownIds = timeline.mapNotNullTo(HashSet()) { it.eventId }
        val fresh = timelineEvents.filter { it.eventId == null || it.eventId !in knownIds }
        val isGap = dto.timeline?.limited == true
        val echoedTxnIds = fresh.mapNotNullTo(HashSet()) { it.transactionId }

        return copy(
            state = newState,
            // A limited timeline means we skipped events; drop the old ones instead of leaving a hole.
            timeline = if (isGap) fresh else timeline + fresh,
            pending = pending.filterNot { it.transactionId in echoedTxnIds },
            heroes = dto.summary?.heroes ?: heroes,
            joinedMemberCount = dto.summary?.joinedMemberCount ?: joinedMemberCount,
            unreadCount = dto.unreadNotifications?.notificationCount ?: unreadCount,
            prevBatch = if (isGap || prevBatch == null) dto.timeline?.prevBatch ?: prevBatch else prevBatch,
            hasMoreHistory = if (isGap) true else hasMoreHistory
        ).withEphemeral(dto.ephemeral?.events.orEmpty())
    }

    private fun withEphemeral(events: List<EphemeralEventDto>): RoomCache {
        var receipts = receipts
        var typing = typingUserIds
        events.forEach { event ->
            when (event.type) {
                // content: { "$event": { "m.read": { "@user": { "ts": 123 } } } }
                "m.receipt" -> event.content.forEach { (eventId, byType) ->
                    val readers = (byType as? JsonObject)?.obj("m.read")?.keys.orEmpty()
                    receipts = receipts + readers.associateWith { eventId }
                }
                "m.typing" -> typing = event.content["user_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            }
        }
        return copy(receipts = receipts, typingUserIds = typing)
    }

    fun prependHistory(response: MessagesResponseDto): RoomCache {
        val knownIds = timeline.mapNotNullTo(HashSet()) { it.eventId }
        val older = response.chunk.reversed().filter { it.eventId !in knownIds }
        val newState = state.toMutableMap()
        // Members returned with history are only used when we don't know them yet.
        (response.state + older.filter { it.stateKey != null })
            .forEach { newState.getOrPut(it.stateCacheKey) { it } }
        return copy(
            state = newState,
            timeline = older + timeline,
            prevBatch = response.end,
            hasMoreHistory = response.end != null && response.chunk.isNotEmpty()
        )
    }
}

data class PendingMessage(
    val transactionId: String,
    val body: String,
    val timestamp: Long,
    val eventId: String? = null,
    val isFailed: Boolean = false,
    val replyToId: String? = null,
    /** Set while an attachment uploads; shown as a placeholder bubble. */
    val attachmentMimeType: String? = null
)
