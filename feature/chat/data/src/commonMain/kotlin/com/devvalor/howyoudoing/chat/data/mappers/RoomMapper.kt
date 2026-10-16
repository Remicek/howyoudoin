package com.devvalor.howyoudoing.chat.data.mappers

import com.devvalor.howyoudoing.chat.data.matrix.PendingMessage
import com.devvalor.howyoudoing.chat.data.matrix.RoomCache
import com.devvalor.howyoudoing.chat.data.matrix.dto.MatrixEventDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.obj
import com.devvalor.howyoudoing.chat.data.matrix.dto.string
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

private const val TYPE_MEMBER = "m.room.member"
private val BRIDGE_STATE_TYPES = setOf("m.bridge", "uk.half-shot.bridge")

fun RoomCache.toChat(myUserId: String): Chat {
    val messages = toMessages(myUserId)
    return Chat(
        id = roomId,
        name = displayName(myUserId),
        network = network(),
        lastMessage = messages.lastOrNull(),
        unreadCount = unreadCount,
        isGroup = memberCount() > 2,
        isEncrypted = isRemoteEncrypted(),
        avatarUrl = stateEvent("m.room.avatar")?.content?.string("url")
    )
}

fun RoomCache.toMessages(myUserId: String): List<Message> {
    val edits = timeline
        .filter { it.relationType() == "m.replace" }
        .mapNotNull { edit ->
            val targetId = edit.content.obj("m.relates_to")?.string("event_id") ?: return@mapNotNull null
            val newBody = edit.content.obj("m.new_content")?.string("body") ?: return@mapNotNull null
            targetId to newBody
        }
        .toMap()

    val confirmed = timeline
        .filter { it.relationType() != "m.replace" }
        .mapNotNull { it.toMessage(roomId, myUserId, edits) }
    val confirmedIds = confirmed.mapTo(HashSet()) { it.id }
    val pendingMessages = pending
        .filterNot { it.eventId != null && it.eventId in confirmedIds }
        .map { it.toMessage(roomId, myUserId) }

    return confirmed + pendingMessages
}

private fun MatrixEventDto.toMessage(
    roomId: String,
    myUserId: String,
    edits: Map<String, String>
): Message? {
    val id = eventId ?: return null
    val sender = sender ?: return null
    val messageType = when {
        // mautrix bridges flag messages they couldn't convert; the body is a technical notice
        content["fi.mau.unsupported"]?.toString() == "true" -> MessageType.UNSUPPORTED
        else -> when (type) {
            "m.room.message" -> msgType() ?: return null // no msgtype means redacted
            "m.sticker" -> MessageType.STICKER
            "m.room.encrypted" -> MessageType.ENCRYPTED
            else -> return null
        }
    }
    return Message(
        id = id,
        chatId = roomId,
        senderId = sender,
        senderName = "",
        body = edits[id] ?: content.string("body").orEmpty(),
        timestamp = originServerTs ?: 0L,
        isOutgoing = sender == myUserId,
        type = messageType
    )
}

private fun PendingMessage.toMessage(roomId: String, myUserId: String) = Message(
    id = transactionId,
    chatId = roomId,
    senderId = myUserId,
    senderName = "",
    body = body,
    timestamp = timestamp,
    isOutgoing = true,
    type = MessageType.TEXT,
    status = if (isFailed) MessageStatus.FAILED else MessageStatus.SENDING
)

/** Fills in sender display names, done separately so it can use room member state. */
fun RoomCache.withSenderNames(messages: List<Message>): List<Message> =
    messages.map { it.copy(senderName = memberName(it.senderId)) }

private fun MatrixEventDto.msgType(): MessageType? = when (content.string("msgtype")) {
    null -> null
    "m.text" -> MessageType.TEXT
    "m.notice" -> MessageType.NOTICE
    "m.emote" -> MessageType.EMOTE
    "m.image" -> MessageType.IMAGE
    "m.video" -> MessageType.VIDEO
    "m.audio" -> MessageType.AUDIO
    "m.file" -> MessageType.FILE
    else -> MessageType.OTHER
}

private fun MatrixEventDto.relationType(): String? =
    content.obj("m.relates_to")?.string("rel_type")

fun RoomCache.memberName(userId: String): String =
    stateEvent(TYPE_MEMBER, userId)?.content?.string("displayname")?.takeIf { it.isNotBlank() }
        ?: userId.removePrefix("@").substringBefore(":")

private fun RoomCache.joinedMembers(): List<String> =
    state.values
        .filter { it.type == TYPE_MEMBER && it.content.string("membership") == "join" }
        .mapNotNull { it.stateKey }

/**
 * Bridge bots announced in `m.bridge` (`bridgebot`). They sit in portal rooms for bookkeeping
 * but aren't people you're chatting with, so they're left out of names and member counts.
 */
private fun RoomCache.bridgeBots(): Set<String> =
    state.values
        .filter { it.type in BRIDGE_STATE_TYPES }
        .mapNotNullTo(HashSet()) { it.content.string("bridgebot") }

/**
 * mautrix bridges describe a chat's capabilities in `com.beeper.room_features`; Messenger's end-to-end
 * encrypted chats get an id like "fi.mau.meta.capabilities.2026_07_21+e2e".
 */
private fun RoomCache.isRemoteEncrypted(): Boolean =
    // State key is per bridge (e.g. "howyoudoin.local/messenger"), so match on type only
    state.values.any { it.type == "com.beeper.room_features" && it.content.string("id")?.contains("+e2e") == true }

/** People in the room, not counting bridge bots. */
private fun RoomCache.memberCount(): Int {
    val bots = bridgeBots()
    val joinedBots = joinedMembers().count { it in bots }
    return (joinedMemberCount.takeIf { it > 0 } ?: joinedMembers().size) - joinedBots
}

private fun RoomCache.displayName(myUserId: String): String {
    stateEvent("m.room.name")?.content?.string("name")?.takeIf { it.isNotBlank() }?.let { return it }
    stateEvent("m.room.canonical_alias")?.content?.string("alias")?.takeIf { it.isNotBlank() }?.let { return it }
    val bots = bridgeBots()
    val others = heroes.ifEmpty { joinedMembers() }.filter { it != myUserId && it !in bots }
    return others.take(4).joinToString(", ") { memberName(it) }.ifBlank { "Empty chat" }
}

private fun RoomCache.network(): ChatNetwork {
    val fromBridgeState = state.values
        .firstOrNull { it.type in BRIDGE_STATE_TYPES }
        ?.content?.obj("protocol")?.string("id")
        ?.let(ChatNetwork::fromProtocolId)
    if (fromBridgeState != null) return fromBridgeState

    return (heroes + joinedMembers())
        .firstNotNullOfOrNull(ChatNetwork::fromUserId)
        ?: ChatNetwork.MATRIX
}
