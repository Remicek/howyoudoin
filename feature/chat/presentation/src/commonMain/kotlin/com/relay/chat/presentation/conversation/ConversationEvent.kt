package com.relay.chat.presentation.conversation

import com.relay.core.presentation.util.UiText

sealed interface ConversationEvent {
    data class Error(val message: UiText) : ConversationEvent
}
