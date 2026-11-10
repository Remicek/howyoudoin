package com.devvalor.howyoudoing.chat.presentation.inbox

import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.chat.presentation.model.ChatUi
import com.devvalor.howyoudoing.chat.presentation.model.AccountTagUi
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionProblem

data class InboxState(
    val chats: List<ChatUi> = emptyList(),
    val query: String = "",
    val filter: InboxFilter = InboxFilter.All,
    val networksInInbox: List<ChatNetwork> = emptyList(),
    /** The networks with more than one account here: a filter per account under the network's. */
    val accountsInInbox: Map<ChatNetwork, List<AccountFilterUi>> = emptyMap(),
    /** The tags of the accounts that need telling apart, e.g. for which one stopped working. */
    val accountTags: Map<AccountRef, AccountTagUi> = emptyMap(),
    /** Networks with at least one end-to-end encrypted chat (regardless of search / filter). */
    val encryptedNetworks: Set<ChatNetwork> = emptySet(),
    val syncState: SyncState = SyncState.IDLE,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /** Connected networks that stopped working. */
    val connectionProblems: List<ConnectionProblem> = emptyList(),
    /** No internet: the chats saved on this phone stay readable, nothing new arrives or gets sent. */
    val isOffline: Boolean = false,
    /** A new user: walk them through the inbox once. */
    val showTour: Boolean = false
) {
    val totalUnread: Int get() = chats.sumOf { it.unreadCount }
}

sealed interface InboxFilter {
    data object All : InboxFilter
    data object Unread : InboxFilter
    /** One network's chats; with [slot], only that account's. */
    data class Network(val network: ChatNetwork, val slot: Int? = null) : InboxFilter
}

data class AccountFilterUi(val slot: Int, val tag: AccountTagUi, val unread: Int)
