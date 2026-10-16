package com.devvalor.howyoudoing.chat.presentation.model

import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.presentation.util.TimestampFormatter

data class ChatUi(
    val id: String,
    val name: String,
    val network: ChatNetwork,
    val preview: String,
    val timestamp: String,
    val unreadCount: Int,
    val isGroup: Boolean
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
    val dayHeader: String? = null
)

fun Chat.toChatUi(): ChatUi = ChatUi(
    id = id,
    name = name,
    network = network,
    preview = lastMessage?.previewText(isGroup).orEmpty(),
    timestamp = lastMessage?.let { TimestampFormatter.formatChatTimestamp(it.timestamp) }.orEmpty(),
    unreadCount = unreadCount,
    isGroup = isGroup
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
            } else null
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
