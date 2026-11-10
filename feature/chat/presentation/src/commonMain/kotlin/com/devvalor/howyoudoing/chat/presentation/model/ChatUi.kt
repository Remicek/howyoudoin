package com.devvalor.howyoudoing.chat.presentation.model

import com.devvalor.howyoudoing.chat.domain.CallInfo
import com.devvalor.howyoudoing.chat.domain.CallOutcome
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.MediaInfo
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.chat.domain.Reaction
import com.devvalor.howyoudoing.chat.domain.ReplyPreview
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.presentation.util.TimestampFormatter
import com.devvalor.howyoudoing.core.presentation.util.UiText
import com.devvalor.howyoudoing.core.presentation.util.asUiText
import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.chat_encrypted_empty
import howyoudoin.feature.chat.presentation.generated.resources.call_incoming
import howyoudoin.feature.chat.presentation.generated.resources.call_incoming_video
import howyoudoin.feature.chat.presentation.generated.resources.call_missed
import howyoudoin.feature.chat.presentation.generated.resources.call_missed_video
import howyoudoin.feature.chat.presentation.generated.resources.call_declined
import howyoudoin.feature.chat.presentation.generated.resources.call_declined_video
import howyoudoin.feature.chat.presentation.generated.resources.call_ended
import howyoudoin.feature.chat.presentation.generated.resources.call_ended_video
import howyoudoin.feature.chat.presentation.generated.resources.call_ended_duration
import howyoudoin.feature.chat.presentation.generated.resources.call_ended_video_duration
import howyoudoin.feature.chat.presentation.generated.resources.call_started
import howyoudoin.feature.chat.presentation.generated.resources.call_started_video
import howyoudoin.feature.chat.presentation.generated.resources.preview_you
import howyoudoin.feature.chat.presentation.generated.resources.preview_sender
import howyoudoin.feature.chat.presentation.generated.resources.type_photo
import howyoudoin.feature.chat.presentation.generated.resources.type_video
import howyoudoin.feature.chat.presentation.generated.resources.type_voice
import howyoudoin.feature.chat.presentation.generated.resources.type_file
import howyoudoin.feature.chat.presentation.generated.resources.type_file_named
import howyoudoin.feature.chat.presentation.generated.resources.type_sticker
import howyoudoin.feature.chat.presentation.generated.resources.type_encrypted
import howyoudoin.feature.chat.presentation.generated.resources.type_one_time_code
import howyoudoin.feature.chat.presentation.generated.resources.type_unsupported
import howyoudoin.feature.chat.presentation.generated.resources.type_unsupported_short
import howyoudoin.feature.chat.presentation.generated.resources.typing_one_to_one
import howyoudoin.feature.chat.presentation.generated.resources.typing_one
import howyoudoin.feature.chat.presentation.generated.resources.typing_many

data class ChatUi(
    val id: String,
    val name: String,
    val network: ChatNetwork,
    val preview: UiText,
    val timestamp: UiText,
    val unreadCount: Int,
    val isGroup: Boolean,
    val isEncrypted: Boolean = false,
    val isOnDevice: Boolean = false,
    /** "Anna is typing…" replaces the preview while someone types. */
    val typingText: UiText? = null,
    val avatarUrl: String? = null,
    /** No notifications for this chat. */
    val isMuted: Boolean = false
)

data class MessageUi(
    val id: String,
    val body: UiText,
    val time: String,
    val isOutgoing: Boolean,
    val senderName: String?,
    val status: MessageStatus,
    /** Who wrote it, even where [senderName] isn't shown (one-to-one chats): for "Replying to …". */
    val authorName: String? = null,
    val type: MessageType,
    /** Set on the first message of each day, e.g. "Yesterday". */
    val dayHeader: UiText? = null,
    /** Plain text for copying / quoting (no media placeholders). */
    val text: String = "",
    val replyTo: ReplyPreview? = null,
    val reactions: List<Reaction> = emptyList(),
    val media: MediaInfo? = null,
    val isReadByOthers: Boolean = false,
    val isEdited: Boolean = false,
    val canRetry: Boolean = false,
    /** A call from the other person that can be returned through a link ("Call back here"): whether it was video. */
    val callBackVideo: Boolean? = null
)

fun typingText(names: List<String>, isGroup: Boolean): UiText? = when {
    names.isEmpty() -> null
    !isGroup -> UiText.Resource(Res.string.typing_one_to_one)
    names.size == 1 -> UiText.Resource(Res.string.typing_one, arrayOf(names.first().substringBefore(" ")))
    else -> UiText.Plural(Res.plurals.typing_many, names.size)
}

fun Chat.toChatUi(): ChatUi = ChatUi(
    id = id,
    name = name,
    network = network,
    preview = lastMessage?.previewText(isGroup, network)
        ?: if (isEncrypted) UiText.Resource(Res.string.chat_encrypted_empty) else "".asUiText(),
    timestamp = lastMessage?.let { TimestampFormatter.formatChatTimestamp(it.timestamp) } ?: "".asUiText(),
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
fun List<Message>.toMessageUis(showSenderNames: Boolean, network: ChatNetwork? = null): List<MessageUi> =
    mapIndexed { index, message ->
        val previous = getOrNull(index - 1)
        val startsRun = previous == null || previous.senderId != message.senderId
        MessageUi(
            id = message.id,
            body = message.displayBody(network),
            time = TimestampFormatter.formatMessageTime(message.timestamp),
            isOutgoing = message.isOutgoing,
            senderName = message.senderName.takeIf { showSenderNames && startsRun && !message.isOutgoing },
            status = message.status,
            authorName = message.senderName.takeIf { it.isNotBlank() },
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
            canRetry = message.canRetry,
            callBackVideo = message.call
                ?.takeIf { !message.isOutgoing && it.state in CALL_BACK_STATES && network != ChatNetwork.MATRIX }
                ?.isVideo
        )
    }

/** Calls not talked through: ringing now, missed or turned down. */
private val CALL_BACK_STATES = setOf(CallOutcome.INCOMING, CallOutcome.MISSED, CallOutcome.DECLINED)

private fun Message.previewText(isGroup: Boolean, network: ChatNetwork): UiText = when {
    // A call says who it was from itself ("Missed call" in the chat with them)
    type == MessageType.CALL -> displayBody(network)
    isOutgoing -> UiText.Resource(Res.string.preview_you, arrayOf(displayBody(network)))
    isGroup && senderName.isNotBlank() -> UiText.Resource(
        Res.string.preview_sender,
        arrayOf(senderName.substringBefore(" "), displayBody(network))
    )

    else -> displayBody(network)
}

/** @param network the chat's network, named where a call can be answered or joined */
private fun Message.displayBody(network: ChatNetwork?): UiText = when (type) {
    MessageType.CALL -> call?.describe(network?.displayName.orEmpty()) ?: body.asUiText()
    MessageType.TEXT, MessageType.NOTICE -> body.asUiText()
    MessageType.EMOTE -> "* $body".asUiText()
    MessageType.IMAGE -> UiText.Resource(Res.string.type_photo)
    MessageType.VIDEO -> UiText.Resource(Res.string.type_video)
    MessageType.AUDIO -> UiText.Resource(Res.string.type_voice)
    MessageType.FILE -> if (body.isBlank()) UiText.Resource(Res.string.type_file) else UiText.Resource(
        Res.string.type_file_named,
        arrayOf(body)
    )

    MessageType.STICKER -> UiText.Resource(Res.string.type_sticker)
    MessageType.ENCRYPTED -> UiText.Resource(Res.string.type_encrypted)
    MessageType.UNSUPPORTED -> UiText.Resource(Res.string.type_unsupported)
    MessageType.ONE_TIME_CODE -> UiText.Resource(Res.string.type_one_time_code)
    MessageType.OTHER -> if (body.isBlank()) UiText.Resource(Res.string.type_unsupported_short) else body.asUiText()
}

/** "Incoming video call · answer it in WhatsApp", "Missed call", "Call · 1:15"… */
fun CallInfo.describe(networkName: String): UiText {
    val duration = durationMillis?.takeIf { it >= MILLIS_PER_SECOND }?.let { formatCallDuration(it) }
    return when (state) {
        CallOutcome.INCOMING -> UiText.Resource(
            if (isVideo) Res.string.call_incoming_video else Res.string.call_incoming,
            arrayOf(networkName)
        )

        CallOutcome.STARTED -> UiText.Resource(
            if (isVideo) Res.string.call_started_video else Res.string.call_started,
            arrayOf(networkName)
        )

        CallOutcome.MISSED -> UiText.Resource(if (isVideo) Res.string.call_missed_video else Res.string.call_missed)
        CallOutcome.DECLINED -> UiText.Resource(
            if (isVideo) Res.string.call_declined_video else Res.string.call_declined
        )
        CallOutcome.ENDED -> if (duration != null) {
            UiText.Resource(
                if (isVideo) Res.string.call_ended_video_duration else Res.string.call_ended_duration,
                arrayOf(duration)
            )
        } else {
            UiText.Resource(if (isVideo) Res.string.call_ended_video else Res.string.call_ended)
        }
    }
}

/** 1:05, or 1:02:05 for calls over an hour. */
internal fun formatCallDuration(millis: Long): String {
    val total = millis / MILLIS_PER_SECOND
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = (total % SECONDS_PER_MINUTE).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
