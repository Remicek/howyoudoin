package com.devvalor.howyoudoing.chat.data.matrix.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SyncResponseDto(
    @SerialName("next_batch") val nextBatch: String,
    val rooms: SyncRoomsDto? = null
)

@Serializable
data class SyncRoomsDto(
    val join: Map<String, JoinedRoomDto> = emptyMap(),
    val invite: Map<String, InvitedRoomDto> = emptyMap(),
    val leave: Map<String, JsonObject> = emptyMap()
)

@Serializable
data class JoinedRoomDto(
    val state: EventListDto? = null,
    val timeline: TimelineDto? = null,
    val summary: RoomSummaryDto? = null,
    @SerialName("unread_notifications") val unreadNotifications: UnreadNotificationsDto? = null,
    val ephemeral: EphemeralListDto? = null
)

/** Receipts (`m.receipt`) and typing (`m.typing`); latest values, not timeline events. */
@Serializable
data class EphemeralListDto(
    val events: List<EphemeralEventDto> = emptyList()
)

@Serializable
data class EphemeralEventDto(
    val type: String,
    val content: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class InvitedRoomDto(
    @SerialName("invite_state") val inviteState: EventListDto? = null
)

@Serializable
data class EventListDto(
    val events: List<MatrixEventDto> = emptyList()
)

@Serializable
data class TimelineDto(
    val events: List<MatrixEventDto> = emptyList(),
    @SerialName("prev_batch") val prevBatch: String? = null,
    val limited: Boolean = false
)

@Serializable
data class RoomSummaryDto(
    @SerialName("m.heroes") val heroes: List<String>? = null,
    @SerialName("m.joined_member_count") val joinedMemberCount: Int? = null
)

@Serializable
data class UnreadNotificationsDto(
    @SerialName("notification_count") val notificationCount: Int? = null,
    @SerialName("highlight_count") val highlightCount: Int? = null
)
