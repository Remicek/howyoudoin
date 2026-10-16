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
    val status: MessageStatus = MessageStatus.SENT,
    val replyTo: ReplyPreview? = null,
    val reactions: List<Reaction> = emptyList(),
    val media: MediaInfo? = null,
    /** For outgoing messages: someone else in the chat has read up to (at least) this message. */
    val isReadByOthers: Boolean = false
)

/** The message this one replies to, as shown in the quote above it. */
data class ReplyPreview(
    val messageId: String,
    val senderName: String,
    val body: String
)

data class Reaction(
    val key: String,
    val count: Int,
    /** Set when the current user reacted with [key]; removing the reaction redacts this event. */
    val ownReactionId: String?
) {
    val isOwn: Boolean get() = ownReactionId != null
}

/** Photo / video / audio / file attached to a message. [url] is an `mxc://` URI. */
data class MediaInfo(
    val url: String,
    val mimeType: String?,
    val fileName: String?,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val durationMillis: Long?
)

/** A file picked by the user, ready to upload. */
class Attachment(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

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
