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
    val isReadByOthers: Boolean = false,
    val isEdited: Boolean = false,
    /** A message that failed to send and can be sent again (text; attachments have to be picked again). */
    val canRetry: Boolean = false,
    /** Set for [MessageType.CALL]: a call on the network, which rings in the network's own app. */
    val call: CallInfo? = null
)

/** A call on a network HowYouDoin can't answer calls of (everything but Matrix): it's shown in the chat instead. */
data class CallInfo(
    val state: CallOutcome,
    val isVideo: Boolean,
    /** How long it lasted, when it was answered and the network says. */
    val durationMillis: Long? = null
)

enum class CallOutcome {
    /** Ringing now, in the network's app. */
    INCOMING,
    MISSED,
    DECLINED,
    ENDED,

    /** A group call or huddle someone started, which can be joined from the network's app. */
    STARTED;

    companion object {
        fun fromId(id: String): CallOutcome = when (id) {
            "incoming" -> INCOMING
            "missed" -> MISSED
            "declined" -> DECLINED
            "started" -> STARTED
            else -> ENDED
        }
    }
}

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

/** A file picked by the user, or a voice message they recorded, ready to upload. */
typealias Attachment = com.devvalor.howyoudoing.core.domain.media.Attachment

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

    /** A business's one-time login code: WhatsApp shows it only on the phone, not on linked devices. */
    ONE_TIME_CODE,

    /** A call on the network ([Message.call] says which kind and how it went). */
    CALL,
    OTHER
}

enum class MessageStatus {
    SENDING,
    SENT,
    FAILED
}
