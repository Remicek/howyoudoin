package com.relay.chat.presentation.inbox

sealed interface InboxAction {
    data class OnQueryChange(val query: String) : InboxAction
    data class OnFilterSelect(val filter: InboxFilter) : InboxAction
    data class OnChatClick(val chatId: String) : InboxAction
    data object OnNetworksClick : InboxAction
    data object OnLogoutClick : InboxAction
}
