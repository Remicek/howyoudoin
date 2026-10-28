package com.devvalor.howyoudoing.chat.data.mappers

import com.devvalor.howyoudoing.chat.data.matrix.PendingMessage
import com.devvalor.howyoudoing.chat.data.matrix.RoomCache
import com.devvalor.howyoudoing.chat.data.matrix.dto.MatrixEventDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.obj
import com.devvalor.howyoudoing.chat.data.matrix.dto.string
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.MediaInfo
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.chat.domain.Reaction
import com.devvalor.howyoudoing.chat.domain.ReplyPreview
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
        avatarUrl = stateEvent("m.room.avatar")?.content?.string("url"),
        typingNames = typingUserIds.filter { it != myUserId }.map { memberName(it) }
    )
}

/** The newest text of each edited message, by the edited message's ID. */
private fun RoomCache.editsByTarget(redacted: Set<String>): Map<String, String> = timeline
    .filter { it.relationType() == "m.replace" && it.eventId !in redacted }
    .mapNotNull { edit ->
        val targetId = edit.content.obj("m.relates_to")?.string("event_id") ?: return@mapNotNull null
        val newBody = edit.content.obj("m.new_content")?.string("body") ?: return@mapNotNull null
        targetId to newBody
    }
    .toMap()

private fun RoomCache.reactionsByMessage(myUserId: String, redacted: Set<String>): Map<String, List<Reaction>> =
    timeline
        .filter { it.type == "m.reaction" && it.eventId !in redacted && it.relationType() == "m.annotation" }
        .groupBy { it.content.obj("m.relates_to")?.string("event_id").orEmpty() }
        .mapValues { (_, events) ->
            events.groupBy { it.content.obj("m.relates_to")?.string("key").orEmpty() }
                .filterKeys { it.isNotEmpty() }
                .map { (key, byKey) ->
                    Reaction(
                        key = key,
                        count = byKey.distinctBy { it.sender }.size,
                        ownReactionId = byKey.firstOrNull { it.sender == myUserId }?.eventId
                    )
                }
        }

fun RoomCache.toMessages(myUserId: String): List<Message> {
    val redacted = timeline
        .filter { it.type == "m.room.redaction" }
        .mapNotNullTo(HashSet()) { it.content.string("redacts") }

    val edits = editsByTarget(redacted)
    val reactions = reactionsByMessage(myUserId, redacted)

    val confirmed = timeline
        .filter { it.relationType() != "m.replace" && it.eventId !in redacted }
        .mapNotNull { it.toMessage(roomId, myUserId, edits) }
        .withoutRepeatedCallNotices()
    val byId = confirmed.associateBy { it.id }

    // Read state: the furthest timeline position anyone else (people, not bridge bots) has read
    val positions = timeline.mapIndexedNotNull { index, event -> event.eventId?.let { it to index } }.toMap()
    val bots = bridgeBots()
    val furthestReadByOthers = receipts
        .filterKeys { it != myUserId && it !in bots }
        .values.mapNotNull { positions[it] }.maxOrNull() ?: -1

    val withRelations = confirmed.map { message ->
        val replyToId = timeline.firstOrNull { it.eventId == message.id }?.replyToId()
        message.copy(
            reactions = reactions[message.id].orEmpty(),
            replyTo = replyToId?.let { id -> replyPreview(id, byId[id]) },
            isReadByOthers = message.isOutgoing && (positions[message.id] ?: Int.MAX_VALUE) <= furthestReadByOthers
        )
    }

    val confirmedIds = byId.keys
    val pendingMessages = pending
        .filterNot { it.eventId != null && it.eventId in confirmedIds }
        .map { pending ->
            pending.toMessage(roomId, myUserId).copy(replyTo = pending.replyToId?.let { replyPreview(it, byId[it]) })
        }

    return withRelations + pendingMessages
}

private fun RoomCache.replyPreview(eventId: String, target: Message?) = ReplyPreview(
    messageId = eventId,
    senderName = target?.senderId?.let { memberName(it) } ?: "",
    body = target?.let { previewText(it) } ?: "Original message"
)

private fun previewText(message: Message): String = when (message.type) {
    MessageType.IMAGE -> "📷 Photo"
    MessageType.VIDEO -> "🎬 Video"
    MessageType.AUDIO -> "🎤 Audio"
    MessageType.FILE -> "📎 ${message.media?.fileName ?: message.body}"
    MessageType.STICKER -> "Sticker"
    else -> message.body
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
            // Someone started or joined an Element (MatrixRTC) call; leaving it has empty content
            in ELEMENT_CALL_TYPES -> if (sender != myUserId && content.isNotEmpty()) MessageType.NOTICE else return null
            else -> return null
        }
    }
    val rawBody = if (type in ELEMENT_CALL_TYPES) ELEMENT_CALL_NOTICE else edits[id] ?: content.string("body").orEmpty()
    return Message(
        id = id,
        chatId = roomId,
        senderId = sender,
        senderName = "",
        body = if (replyToId() != null) stripReplyFallback(rawBody) else rawBody,
        timestamp = originServerTs ?: 0L,
        isOutgoing = sender == myUserId,
        type = messageType,
        media = mediaInfo(messageType),
        isEdited = id in edits
    )
}

private fun MatrixEventDto.mediaInfo(type: MessageType): MediaInfo? {
    if (type !in MEDIA_TYPES) return null
    // Encrypted rooms carry the URL inside "file" (with the key to decrypt it)
    val url = content.string("url") ?: content.obj("file")?.string("url") ?: return null
    val info = content.obj("info")
    return MediaInfo(
        url = url,
        mimeType = info?.string("mimetype"),
        // With a caption, the file name moves to "filename" and body holds the caption
        fileName = content.string("filename") ?: content.string("body"),
        sizeBytes = info?.string("size")?.toLongOrNull(),
        width = info?.string("w")?.toIntOrNull(),
        height = info?.string("h")?.toIntOrNull(),
        durationMillis = info?.string("duration")?.toLongOrNull()
    )
}

/** One call announces itself with several events (membership, ringing); show it once per person and minute. */
private fun List<Message>.withoutRepeatedCallNotices(): List<Message> {
    val lastNotice = mutableMapOf<String, Long>()
    return filter { message ->
        if (message.body != ELEMENT_CALL_NOTICE) return@filter true
        val previous = lastNotice[message.senderId]
        lastNotice[message.senderId] = message.timestamp
        previous == null || message.timestamp - previous > CALL_NOTICE_WINDOW_MILLIS
    }
}

private const val CALL_NOTICE_WINDOW_MILLIS = 60_000L

/** Element's group calls (MatrixRTC): who's in the call, and its ringing notification. */
private val ELEMENT_CALL_TYPES = setOf(
    "org.matrix.msc3401.call.member", "m.call.member", "org.matrix.msc4075.rtc.notification", "m.rtc.notification"
)

private const val ELEMENT_CALL_NOTICE =
    "📞 Started a call in Element. HowYouDoin can't join Element calls yet; call back with the call button above."

private val MEDIA_TYPES =
    setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.FILE, MessageType.STICKER)

private fun MatrixEventDto.replyToId(): String? =
    content.obj("m.relates_to")?.obj("m.in_reply_to")?.string("event_id")

/** Older clients quote the original as "> <@user> text" lines before the reply (MSC2781 removed it). */
private fun stripReplyFallback(body: String): String {
    if (!body.startsWith("> ")) return body
    val lines = body.lines()
    val firstNonQuote = lines.indexOfFirst { !it.startsWith(">") }
    return if (firstNonQuote < 0) body else lines.drop(firstNonQuote).dropWhile { it.isBlank() }.joinToString("\n")
}

private fun PendingMessage.toMessage(roomId: String, myUserId: String) = Message(
    id = transactionId,
    chatId = roomId,
    senderId = myUserId,
    senderName = "",
    body = body,
    timestamp = timestamp,
    isOutgoing = true,
    type = when {
        attachmentMimeType == null -> MessageType.TEXT
        attachmentMimeType.startsWith("image/") -> MessageType.IMAGE
        attachmentMimeType.startsWith("video/") -> MessageType.VIDEO
        attachmentMimeType.startsWith("audio/") -> MessageType.AUDIO
        else -> MessageType.FILE
    },
    status = if (isFailed) MessageStatus.FAILED else MessageStatus.SENDING,
    canRetry = isFailed && attachmentMimeType == null
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

private fun RoomCache.displayName(myUserId: String): String =
    stateEvent("m.room.name")?.content?.string("name")?.takeIf { it.isNotBlank() }
        ?: stateEvent("m.room.canonical_alias")?.content?.string("alias")?.takeIf { it.isNotBlank() }
        ?: membersName(myUserId)

/** An unnamed chat is named after the people in it. */
private fun RoomCache.membersName(myUserId: String): String {
    val bots = bridgeBots()
    val others = heroes.ifEmpty { joinedMembers() }.filter { it != myUserId && it !in bots }.take(NAMED_MEMBERS)
    val label = others.joinToString(", ") { memberName(it) }
    if (label.isBlank()) return "Empty chat"
    // Named after people who haven't joined (yet): otherwise a fresh invite looks like the chat with them
    val memberships = others.map { stateEvent(TYPE_MEMBER, it)?.content?.string("membership") }
    return when {
        memberships.all { it == "invite" } -> "$label (invited)"
        memberships.all { it == "leave" || it == "ban" } -> "$label (left)"
        else -> label
    }
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

/** How many people an unnamed chat is named after. */
private const val NAMED_MEMBERS = 4
