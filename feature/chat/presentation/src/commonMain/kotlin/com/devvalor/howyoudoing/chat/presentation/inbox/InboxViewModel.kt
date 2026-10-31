package com.devvalor.howyoudoing.chat.presentation.inbox

import com.devvalor.howyoudoing.chat.domain.MediaRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.presentation.model.toChatUi
import com.devvalor.howyoudoing.core.domain.auth.AuthService
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import com.devvalor.howyoudoing.chat.domain.NotificationPreferences
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class InboxViewModel(
    private val chatRepository: ChatRepository,
    private val authService: AuthService,
    private val notificationPreferences: NotificationPreferences,
    private val connectionMonitor: ConnectionMonitor
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
            is InboxAction.OnDismissProblem -> connectionMonitor.dismiss(action.network)
            InboxAction.OnReconnectClick -> connectionMonitor.reconnect()
            else -> Unit
        }
    }

    private fun observeChats() {
        combine(
            chatRepository.observeChats(),
            query,
            filter,
            notificationPreferences.observeMutedChats(),
            connectionMonitor.linkedNetworks
        ) { chats, query, filter, muted, linked ->
            val visible = chats
                .filter { chat -> filter.matches(chat) }
                .filter { chat -> query.isBlank() || chat.name.contains(query.trim(), ignoreCase = true) }
                .map { it.toChatUi().copy(isMuted = it.id in muted) }
            // Only networks that are connected get a filter; chats of a disconnected one can stay in the list
            val networks = chats
                .filter { !it.isOnDevice || it.network in linked }
                .map { it.network }
                .distinct()
                .sortedBy { it.ordinal }
            val encrypted = chats.filter { it.isEncrypted }.mapTo(HashSet()) { it.network }
            Triple(visible, networks, encrypted)
        }
            .onEach { (visible, networks, encrypted) ->
                // The selected network was disconnected: back to all chats
                if ((filter.value as? InboxFilter.Network)?.network?.let { it !in networks } == true) {
                    onAction(InboxAction.OnFilterSelect(InboxFilter.All))
                }
                _state.update {
                    it.copy(
                        chats = visible,
                        networksInInbox = networks,
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
        is InboxFilter.Network -> chat.network == network
    }

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
