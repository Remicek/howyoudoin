package com.devvalor.howyoudoing.chat.presentation.conversation

import com.devvalor.howyoudoing.core.presentation.util.UiText

sealed interface ConversationEvent {
    data class Error(val message: UiText) : ConversationEvent
    /** The chat is gone (an invitation was declined). */
    data object Close : ConversationEvent
    data class CopyToClipboard(val text: String) : ConversationEvent
    /** A downloaded file to hand to another app. */
    class OpenFile(val bytes: ByteArray, val fileName: String, val mimeType: String?) : ConversationEvent
}
