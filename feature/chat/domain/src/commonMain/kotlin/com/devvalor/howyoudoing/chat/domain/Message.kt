package com.devvalor.howyoudoing.chat.domain

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

    /** E2EE event HowYouDoin can't decrypt yet. */
    ENCRYPTED,

    /** Something the bridge couldn't convert (e.g. a Messenger-only attachment type). */
    UNSUPPORTED,
    OTHER
}

enum class MessageStatus {
    SENDING,
    SENT,
    FAILED
}
