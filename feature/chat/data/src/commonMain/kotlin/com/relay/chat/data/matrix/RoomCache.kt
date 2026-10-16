package com.relay.chat.data.matrix

import com.relay.chat.data.matrix.dto.JoinedRoomDto
import com.relay.chat.data.matrix.dto.MatrixEventDto
import com.relay.chat.data.matrix.dto.MessagesResponseDto

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
    val hasMoreHistory: Boolean = true
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
        )
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
    val isFailed: Boolean = false
)
