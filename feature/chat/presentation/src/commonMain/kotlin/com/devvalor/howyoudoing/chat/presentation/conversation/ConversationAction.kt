package com.devvalor.howyoudoing.chat.presentation.conversation

sealed interface ConversationAction {
    data class OnDraftChange(val draft: String) : ConversationAction
    data object OnSendClick : ConversationAction
    data object OnLoadOlder : ConversationAction
    data object OnBackClick : ConversationAction
}
