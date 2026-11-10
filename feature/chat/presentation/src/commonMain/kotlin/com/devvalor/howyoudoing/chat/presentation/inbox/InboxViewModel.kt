package com.devvalor.howyoudoing.chat.presentation.inbox

import com.devvalor.howyoudoing.chat.domain.MediaRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.presentation.model.toChatUi
import com.devvalor.howyoudoing.chat.presentation.model.ChatUi
import com.devvalor.howyoudoing.chat.presentation.model.accountTag
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.core.domain.ondevice.AccountTags
import com.devvalor.howyoudoing.core.domain.app.AppTour
import com.devvalor.howyoudoing.core.domain.auth.AuthService
import com.devvalor.howyoudoing.core.domain.connectivity.InternetConnection
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import com.devvalor.howyoudoing.chat.domain.NotificationPreferences
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("LongParameterList") // Koin-injected collaborators
class InboxViewModel(
    private val chatRepository: ChatRepository,
    private val authService: AuthService,
    private val notificationPreferences: NotificationPreferences,
    private val connectionMonitor: ConnectionMonitor,
    private val internetConnection: InternetConnection,
    private val appTour: AppTour,
    private val accountTags: AccountTags
) : ViewModel() {

    private var hasLoadedInitialData = false

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow<InboxFilter>(InboxFilter.All)

    private val _state = MutableStateFlow(InboxState())
    val state = _state
        .onStart {
            if (!hasLoadedInitialData) {
                observeChats()
                observeSyncState()
                connectionMonitor.problems
                    .onEach { problems -> _state.update { it.copy(connectionProblems = problems) } }
                    .launchIn(viewModelScope)
                accountTags.observe()
                    .onEach { tags ->
                        val tagUis = tags.keys
                            .mapNotNull { account -> accountTag(account, tags)?.let { account to it } }
                            .toMap()
                        _state.update { it.copy(accountTags = tagUis) }
                    }
                    .launchIn(viewModelScope)
                internetConnection.isOnline
                    .onEach { online -> _state.update { it.copy(isOffline = !online) } }
                    .launchIn(viewModelScope)
                appTour.observeShouldShow()
                    .onEach { show -> _state.update { it.copy(showTour = show) } }
                    .launchIn(viewModelScope)
                hasLoadedInitialData = true
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = InboxState()
        )

    fun onAction(action: InboxAction) {
        when (action) {
            is InboxAction.OnQueryChange -> {
                query.value = action.query
                _state.update { it.copy(query = action.query) }
            }

            is InboxAction.OnFilterSelect -> {
                filter.value = action.filter
                _state.update { it.copy(filter = action.filter) }
            }

            InboxAction.OnLogoutClick -> viewModelScope.launch { authService.logout() }
            InboxAction.OnRefresh -> refresh()
            is InboxAction.OnDismissProblem -> connectionMonitor.dismiss(action.account)
            InboxAction.OnReconnectClick -> connectionMonitor.reconnect()
            InboxAction.OnTourDone -> {
                _state.update { it.copy(showTour = false) }
                viewModelScope.launch { appTour.markSeen() }
            }
            else -> Unit
        }
    }

    private fun observeChats() {
        combine(
            chatRepository.observeChats(),
            query,
            filter,
            notificationPreferences.observeMutedChats(),
            combine(connectionMonitor.linkedNetworks, accountTags.observe(), ::Pair)
        ) { chats, query, filter, muted, (linked, tags) ->
            val visible = chats
                .filter { chat -> filter.matches(chat) }
                .filter { chat -> query.isBlank() || chat.name.contains(query.trim(), ignoreCase = true) }
                .map { chat ->
                    chat.toChatUi().copy(
                        isMuted = chat.id in muted,
                        account = chat.account?.let { accountTag(it, tags) }
                    )
                }
            val accountFilters = tags.keys.groupBy { it.network }.mapValues { (_, accounts) ->
                accounts.sortedBy { it.slot }.mapNotNull { account ->
                    accountTag(account, tags)?.let { tag ->
                        val unread = chats.filter { it.account == account }.sumOf { it.unreadCount }
                        AccountFilterUi(account.slot, tag, unread)
                    }
                }
            }
            // Only networks that are connected get a filter; chats of a disconnected one can stay in the list
            val networks = chats
                .filter { !it.isOnDevice || it.network in linked }
                .map { it.network }
                .distinct()
                .sortedBy { it.ordinal }
            val encrypted = chats.filter { it.isEncrypted }.mapTo(HashSet()) { it.network }
            InboxLists(visible, networks, encrypted, accountFilters)
        }
            // Building the list from every network's chats is too much for the main thread while they pour in, and
            // only the latest list is worth drawing
            .flowOn(Dispatchers.Default)
            .conflate()
            .onEach { lists ->
                val (visible, networks, encrypted) = lists
                val accountFilters = lists.accountFilters
                // The selected network was disconnected: back to all chats
                val selected = filter.value as? InboxFilter.Network
                val accountGone = selected?.slot != null &&
                    accountFilters[selected.network].orEmpty().none { it.slot == selected.slot }
                if (selected != null && (selected.network !in networks || accountGone)) {
                    onAction(InboxAction.OnFilterSelect(InboxFilter.All))
                }
                _state.update {
                    it.copy(
                        chats = visible,
                        networksInInbox = networks,
                        accountsInInbox = accountFilters,
                        encryptedNetworks = encrypted,
                        isLoading = false
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeSyncState() {
        chatRepository.observeSyncState()
            .onEach { syncState -> _state.update { it.copy(syncState = syncState) } }
            .launchIn(viewModelScope)
    }

    private fun InboxFilter.matches(chat: Chat): Boolean = when (this) {
        InboxFilter.All -> true
        InboxFilter.Unread -> chat.unreadCount > 0
        is InboxFilter.Network -> chat.network == network && (slot == null || chat.account?.slot == slot)
    }

    /** A chat on one of the phone's accounts (null for the server's and Matrix's). */
    private val Chat.account: AccountRef? get() = if (isOnDevice) AccountRef(network, accountSlot) else null

    private data class InboxLists(
        val visible: List<ChatUi>,
        val networks: List<ChatNetwork>,
        val encrypted: Set<ChatNetwork>,
        val accountFilters: Map<ChatNetwork, List<AccountFilterUi>>
    )

    private fun refresh() {
        if (state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            chatRepository.refresh()
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    /** Lets the UI's image loader fetch chat pictures (authenticated server media, or direct URLs). */
    suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean): MediaRequest? =
        chatRepository.resolveMedia(mxcUrl, thumbnail)
}
