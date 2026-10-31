package com.devvalor.howyoudoing.chat.presentation.inbox

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

sealed interface InboxAction {
    data class OnQueryChange(val query: String) : InboxAction
    data class OnFilterSelect(val filter: InboxFilter) : InboxAction
    data class OnChatClick(val chatId: String) : InboxAction
    data object OnNetworksClick : InboxAction
    data object OnNewMessageClick : InboxAction
    data object OnContactsClick : InboxAction
    data object OnSettingsClick : InboxAction
    data object OnGuideClick : InboxAction
    data object OnRefresh : InboxAction
    data object OnLogoutClick : InboxAction
    data class OnDismissProblem(val network: ChatNetwork) : InboxAction
    data object OnReconnectClick : InboxAction
}
