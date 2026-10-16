package com.relay.chat.domain

data class Message(
    val id: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val type: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.SENT
)

enum class MessageType {
    TEXT,
    NOTICE,
    EMOTE,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    STICKER,

    /** E2EE event Relay can't decrypt yet. */
    ENCRYPTED,
    OTHER
}

enum class MessageStatus {
    SENDING,
    SENT,
    FAILED
}
