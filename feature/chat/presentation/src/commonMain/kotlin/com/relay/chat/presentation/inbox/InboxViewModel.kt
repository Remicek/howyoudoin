package com.relay.chat.presentation.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.chat.domain.Chat
import com.relay.chat.domain.ChatRepository
import com.relay.chat.presentation.model.toChatUi
import com.relay.core.domain.auth.AuthService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class InboxViewModel(
    private val chatRepository: ChatRepository,
    private val authService: AuthService
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
            else -> Unit
        }
    }

    private fun observeChats() {
        combine(chatRepository.observeChats(), query, filter) { chats, query, filter ->
            val visible = chats
                .filter { chat -> filter.matches(chat) }
                .filter { chat -> query.isBlank() || chat.name.contains(query.trim(), ignoreCase = true) }
                .map { it.toChatUi() }
            val networks = chats.map { it.network }.distinct().sortedBy { it.ordinal }
            visible to networks
        }
            .onEach { (visible, networks) ->
                _state.update {
                    it.copy(chats = visible, networksInInbox = networks, isLoading = false)
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
}
