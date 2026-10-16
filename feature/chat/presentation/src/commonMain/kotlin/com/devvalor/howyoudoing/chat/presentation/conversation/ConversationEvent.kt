package com.devvalor.howyoudoing.chat.presentation.conversation

import com.devvalor.howyoudoing.core.presentation.util.UiText

sealed interface ConversationEvent {
    data class Error(val message: UiText) : ConversationEvent
}
