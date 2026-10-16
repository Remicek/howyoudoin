package com.devvalor.howyoudoing.chat.presentation.inbox

import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.chat.presentation.model.ChatUi
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

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
