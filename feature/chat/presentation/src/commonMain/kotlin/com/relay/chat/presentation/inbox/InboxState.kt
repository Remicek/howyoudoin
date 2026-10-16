package com.relay.chat.presentation.inbox

import com.relay.chat.domain.SyncState
import com.relay.chat.presentation.model.ChatUi
import com.relay.core.domain.network.ChatNetwork

data class InboxState(
    val chats: List<ChatUi> = emptyList(),
    val query: String = "",
    val filter: InboxFilter = InboxFilter.All,
    val networksInInbox: List<ChatNetwork> = emptyList(),
    val syncState: SyncState = SyncState.IDLE,
    val isLoading: Boolean = true
) {
    val totalUnread: Int get() = chats.sumOf { it.unreadCount }
}

sealed interface InboxFilter {
    data object All : InboxFilter
    data object Unread : InboxFilter
    data class Network(val network: ChatNetwork) : InboxFilter
}
