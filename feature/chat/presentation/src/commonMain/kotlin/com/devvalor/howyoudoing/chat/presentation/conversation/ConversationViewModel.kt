package com.devvalor.howyoudoing.chat.presentation.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.presentation.model.toMessageUis
import com.devvalor.howyoudoing.chat.presentation.navigation.ChatRoutes
import com.devvalor.howyoudoing.core.domain.util.onFailure
import com.devvalor.howyoudoing.core.presentation.util.toUiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ConversationViewModel(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val chatId = savedStateHandle.toRoute<ChatRoutes.Conversation>().chatId

    private val eventChannel = Channel<ConversationEvent>()
    val events = eventChannel.receiveAsFlow()

    private var hasLoadedInitialData = false

    private val _state = MutableStateFlow(ConversationState())
    val state = _state
        .onStart {
            if (!hasLoadedInitialData) {
                observeConversation()
                hasLoadedInitialData = true
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = ConversationState()
        )

    fun onAction(action: ConversationAction) {
        when (action) {
            is ConversationAction.OnDraftChange -> _state.update { it.copy(draft = action.draft) }
            ConversationAction.OnSendClick -> send()
            ConversationAction.OnLoadOlder -> loadOlder()
            else -> Unit
        }
    }

    private fun observeConversation() {
        combine(
            chatRepository.observeChat(chatId),
            chatRepository.observeMessages(chatId),
            chatRepository.hasMoreHistory(chatId)
        ) { chat, messages, hasMore ->
            Triple(chat, messages, hasMore)
        }
            .onEach { (chat, messages, hasMore) ->
                _state.update {
                    it.copy(
                        chatName = chat?.name ?: it.chatName,
                        network = chat?.network ?: it.network,
                        isGroup = chat?.isGroup ?: it.isGroup,
                        isEncrypted = chat?.isEncrypted ?: it.isEncrypted,
                        messages = messages.toMessageUis(showSenderNames = chat?.isGroup == true),
                        hasMoreHistory = hasMore
                    )
                }
                // Anything that arrives while the chat is open counts as read.
                if ((chat?.unreadCount ?: 0) > 0) {
                    chatRepository.markAsRead(chatId)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun send() {
        val body = state.value.draft.trim()
        if (body.isEmpty()) return
        _state.update { it.copy(draft = "") }
        viewModelScope.launch {
            chatRepository.sendMessage(chatId, body)
                .onFailure { error -> eventChannel.send(ConversationEvent.Error(error.toUiText())) }
        }
    }

    private fun loadOlder() {
        val current = state.value
        if (current.isLoadingOlder || !current.hasMoreHistory) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingOlder = true) }
            chatRepository.loadOlderMessages(chatId)
                .onFailure { error -> eventChannel.send(ConversationEvent.Error(error.toUiText())) }
            _state.update { it.copy(isLoadingOlder = false) }
        }
    }
}
