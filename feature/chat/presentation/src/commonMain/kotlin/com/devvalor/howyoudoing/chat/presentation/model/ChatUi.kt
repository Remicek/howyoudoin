package com.devvalor.howyoudoing.chat.presentation.model

import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.MediaInfo
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.chat.domain.Reaction
import com.devvalor.howyoudoing.chat.domain.ReplyPreview
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.presentation.util.TimestampFormatter

data class ChatUi(
    val id: String,
    val name: String,
    val network: ChatNetwork,
    val preview: String,
    val timestamp: String,
    val unreadCount: Int,
    val isGroup: Boolean,
    val isEncrypted: Boolean = false,
    val isOnDevice: Boolean = false,
    /** "Anna is typing…" replaces the preview while someone types. */
    val typingText: String? = null,
    val avatarUrl: String? = null,
    /** No notifications for this chat. */
    val isMuted: Boolean = false
)

data class MessageUi(
    val id: String,
    val body: String,
    val time: String,
    val isOutgoing: Boolean,
    val senderName: String?,
    val status: MessageStatus,
    val type: MessageType,
    /** Set on the first message of each day, e.g. "Yesterday". */
    val dayHeader: String? = null,
    /** Plain text for copying / quoting (no media placeholders). */
    val text: String = body,
    val replyTo: ReplyPreview? = null,
    val reactions: List<Reaction> = emptyList(),
    val media: MediaInfo? = null,
    val isReadByOthers: Boolean = false,
    val isEdited: Boolean = false,
    val canRetry: Boolean = false
)

fun typingText(names: List<String>, isGroup: Boolean): String? = when {
    names.isEmpty() -> null
    !isGroup -> "typing…"
    names.size == 1 -> "${names.first().substringBefore(" ")} is typing…"
    else -> "${names.size} people are typing…"
}

fun Chat.toChatUi(): ChatUi = ChatUi(
    id = id,
    name = name,
    network = network,
    preview = lastMessage?.previewText(isGroup)
        ?: if (isEncrypted) "Encrypted chat · new messages will appear here" else "",
    timestamp = lastMessage?.let { TimestampFormatter.formatChatTimestamp(it.timestamp) }.orEmpty(),
    unreadCount = unreadCount,
    isGroup = isGroup,
    isEncrypted = isEncrypted,
    isOnDevice = isOnDevice,
    typingText = typingText(typingNames, isGroup),
    avatarUrl = avatarUrl
)

/**
 * @param showSenderNames group chats label the first message of each run of incoming messages
 */
fun List<Message>.toMessageUis(showSenderNames: Boolean): List<MessageUi> =
    mapIndexed { index, message ->
        val previous = getOrNull(index - 1)
        val startsRun = previous == null || previous.senderId != message.senderId
        MessageUi(
            id = message.id,
            body = message.displayBody(),
            time = TimestampFormatter.formatMessageTime(message.timestamp),
            isOutgoing = message.isOutgoing,
            senderName = message.senderName.takeIf { showSenderNames && startsRun && !message.isOutgoing },
            status = message.status,
            type = message.type,
            dayHeader = if (previous == null || !TimestampFormatter.isSameDay(previous.timestamp, message.timestamp)) {
                TimestampFormatter.formatDayHeader(message.timestamp)
            } else null,
            text = message.body,
            replyTo = message.replyTo,
            reactions = message.reactions,
            media = message.media,
            isReadByOthers = message.isReadByOthers,
            isEdited = message.isEdited,
            canRetry = message.canRetry
        )
    }

private fun Message.previewText(isGroup: Boolean): String {
    val prefix = when {
        isOutgoing -> "You: "
        isGroup && senderName.isNotBlank() -> "${senderName.substringBefore(" ")}: "
        else -> ""
    }
    return prefix + displayBody()
}

private fun Message.displayBody(): String = when (type) {
    MessageType.TEXT, MessageType.NOTICE -> body
    MessageType.EMOTE -> "* $body"
    MessageType.IMAGE -> "📷 Photo"
    MessageType.VIDEO -> "🎬 Video"
    MessageType.AUDIO -> "🎤 Voice message"
    MessageType.FILE -> "📎 ${body.ifBlank { "File" }}"
    MessageType.STICKER -> "Sticker"
    MessageType.ENCRYPTED -> "🔒 Encrypted message"
    MessageType.UNSUPPORTED -> "⚠️ This message type isn't supported yet"
    MessageType.OTHER -> body.ifBlank { "Unsupported message" }
}
