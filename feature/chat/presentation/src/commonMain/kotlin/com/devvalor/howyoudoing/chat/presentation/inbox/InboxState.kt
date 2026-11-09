package com.devvalor.howyoudoing.chat.presentation.inbox

import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.chat.presentation.model.ChatUi
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionProblem

data class InboxState(
    val chats: List<ChatUi> = emptyList(),
    val query: String = "",
    val filter: InboxFilter = InboxFilter.All,
    val networksInInbox: List<ChatNetwork> = emptyList(),
    /** Networks with at least one end-to-end encrypted chat (regardless of search / filter). */
    val encryptedNetworks: Set<ChatNetwork> = emptySet(),
    val syncState: SyncState = SyncState.IDLE,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /** Connected networks that stopped working. */
    val connectionProblems: List<ConnectionProblem> = emptyList(),
    /** No internet: the chats saved on this phone stay readable, nothing new arrives or gets sent. */
    val isOffline: Boolean = false
) {
    val totalUnread: Int get() = chats.sumOf { it.unreadCount }
}

sealed interface InboxFilter {
    data object All : InboxFilter
    data object Unread : InboxFilter
    data class Network(val network: ChatNetwork) : InboxFilter
}
