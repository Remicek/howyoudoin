package com.devvalor.howyoudoing.chat.presentation.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.NotificationPreferences
import com.devvalor.howyoudoing.chat.domain.OpenChatTracker
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.chat.presentation.model.toMessageUis
import com.devvalor.howyoudoing.chat.presentation.model.typingText
import com.devvalor.howyoudoing.chat.presentation.navigation.ChatRoutes
import com.devvalor.howyoudoing.core.domain.util.onFailure
import com.devvalor.howyoudoing.core.presentation.util.UiText
import com.devvalor.howyoudoing.core.presentation.util.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    private val chatRepository: ChatRepository,
    private val openChatTracker: OpenChatTracker,
    private val notificationPreferences: NotificationPreferences
) : ViewModel() {

    private val chatId = savedStateHandle.toRoute<ChatRoutes.Conversation>().chatId

    override fun onCleared() {
        if (openChatTracker.openChatId.value == chatId) openChatTracker.openChatId.value = null
        super.onCleared()
    }

    private val eventChannel = Channel<ConversationEvent>()
    val events = eventChannel.receiveAsFlow()

    private var hasLoadedInitialData = false
    private var isTypingSent = false
    private var stopTypingJob: Job? = null

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

    init {
        // This chat's messages are seen here, so they don't need a notification (or an old one) meanwhile
        openChatTracker.openChatId.value = chatId
        notificationPreferences.observeMutedChats()
            .onEach { muted -> _state.update { it.copy(isMuted = chatId in muted) } }
            .launchIn(viewModelScope)
    }

    fun onAction(action: ConversationAction) {
        when (action) {
            is ConversationAction.OnDraftChange -> {
                _state.update { it.copy(draft = action.draft) }
                updateTyping(action.draft.isNotBlank())
            }
            ConversationAction.OnSendClick -> send()
            ConversationAction.OnLoadOlder -> loadOlder()
            is ConversationAction.OnMessageLongPress -> _state.update { state ->
                state.copy(actionsFor = state.messages.firstOrNull { it.id == action.messageId })
            }
            ConversationAction.OnDismissActions -> _state.update { it.copy(actionsFor = null) }
            is ConversationAction.OnReactionClick -> react(action.messageId, action.key)
            is ConversationAction.OnReplyClick -> _state.update { state ->
                state.copy(replyingTo = state.messages.firstOrNull { it.id == action.messageId }, actionsFor = null)
            }
            ConversationAction.OnCancelReply -> _state.update { it.copy(replyingTo = null) }
            is ConversationAction.OnCopyClick -> {
                val text = state.value.messages.firstOrNull { it.id == action.messageId }?.text
                _state.update { it.copy(actionsFor = null) }
                if (text != null) viewModelScope.launch { eventChannel.send(ConversationEvent.CopyToClipboard(text)) }
            }
            is ConversationAction.OnAttachmentPicked -> sendAttachment(action.attachment)
            is ConversationAction.OnVoiceRecorded -> sendVoice(action.attachment)
            is ConversationAction.OnAttachmentError -> viewModelScope.launch {
                eventChannel.send(ConversationEvent.Error(UiText.DynamicString(action.message)))
            }
            is ConversationAction.OnMediaClick -> openMedia(action.messageId)
            ConversationAction.OnCloseImage -> _state.update { it.copy(viewingImage = null) }
            ConversationAction.OnToggleMute -> viewModelScope.launch {
                notificationPreferences.setMuted(chatId, !state.value.isMuted)
            }
            ConversationAction.OnSearchClick -> _state.update { it.copy(searchQuery = "") }
            is ConversationAction.OnSearchQueryChange -> _state.update { it.copy(searchQuery = action.query).withSearchMatches() }
            ConversationAction.OnSearchOlder -> _state.update {
                it.copy(searchIndex = (it.searchIndex + 1).coerceAtMost((it.searchMatches.size - 1).coerceAtLeast(0)))
            }
            ConversationAction.OnSearchNewer -> _state.update { it.copy(searchIndex = (it.searchIndex - 1).coerceAtLeast(0)) }
            ConversationAction.OnCloseSearch -> _state.update { it.copy(searchQuery = null, searchMatches = emptyList(), searchIndex = 0) }
            ConversationAction.OnBackClick -> Unit
        }
    }

    /** Recomputes the matches, staying on the current one when it still matches. */
    private fun ConversationState.withSearchMatches(): ConversationState {
        val query = searchQuery?.trim().orEmpty()
        if (query.isEmpty()) return copy(searchMatches = emptyList(), searchIndex = 0)
        val matches = messages.asReversed().filter { it.text.contains(query, ignoreCase = true) }.map { it.id }
        val keep = currentMatchId?.let(matches::indexOf)?.takeIf { it >= 0 }
        return copy(searchMatches = matches, searchIndex = keep ?: 0)
    }

    /** Lets the UI's image loader fetch authenticated media. */
    suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean): MediaRequest? =
        chatRepository.resolveMedia(mxcUrl, thumbnail)

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
                        avatarUrl = chat?.avatarUrl ?: it.avatarUrl,
                        network = chat?.network ?: it.network,
                        isGroup = chat?.isGroup ?: it.isGroup,
                        isEncrypted = chat?.isEncrypted ?: it.isEncrypted,
                        isOnDevice = chat?.isOnDevice ?: it.isOnDevice,
                        typingText = chat?.let { c -> typingText(c.typingNames, c.isGroup) },
                        messages = messages.toMessageUis(showSenderNames = chat?.isGroup == true),
                        hasMoreHistory = hasMore
                    ).let { updated -> if (updated.searchQuery != null) updated.withSearchMatches() else updated }
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
        val replyTo = state.value.replyingTo?.id
        _state.update { it.copy(draft = "", replyingTo = null) }
        updateTyping(false)
        viewModelScope.launch {
            chatRepository.sendMessage(chatId, body, replyTo)
                .onFailure { error -> eventChannel.send(ConversationEvent.Error(error.toUiText())) }
        }
    }

    private fun react(messageId: String, key: String) {
        _state.update { it.copy(actionsFor = null) }
        viewModelScope.launch {
            chatRepository.toggleReaction(chatId, messageId, key)
                .onFailure { error -> eventChannel.send(ConversationEvent.Error(error.toUiText())) }
        }
    }

    private fun sendAttachment(attachment: Attachment) {
        val caption = state.value.draft.trim().takeIf { it.isNotEmpty() }
        _state.update { it.copy(draft = "", isSendingAttachment = true) }
        viewModelScope.launch {
            chatRepository.sendAttachment(chatId, attachment, caption)
                .onFailure { error -> eventChannel.send(ConversationEvent.Error(error.toUiText())) }
            _state.update { it.copy(isSendingAttachment = false) }
        }
    }

    /** A voice message goes without the draft as its caption. */
    private fun sendVoice(attachment: Attachment) {
        _state.update { it.copy(isSendingAttachment = true) }
        viewModelScope.launch {
            chatRepository.sendAttachment(chatId, attachment, caption = null)
                .onFailure { error -> eventChannel.send(ConversationEvent.Error(error.toUiText())) }
            _state.update { it.copy(isSendingAttachment = false) }
        }
    }

    /** Media bytes for in-app playback (voice messages). */
    suspend fun loadMedia(url: String): ByteArray? = chatRepository.downloadMedia(url)

    /** Images open in the in-app viewer; anything else is downloaded and handed to another app. */
    private fun openMedia(messageId: String) {
        val message = state.value.messages.firstOrNull { it.id == messageId } ?: return
        val media = message.media ?: return
        if (message.type == MessageType.IMAGE || message.type == MessageType.STICKER) {
            _state.update { it.copy(viewingImage = message) }
            return
        }
        viewModelScope.launch {
            val bytes = chatRepository.downloadMedia(media.url)
            if (bytes == null) {
                eventChannel.send(ConversationEvent.Error(UiText.DynamicString("Couldn't download the file")))
            } else {
                eventChannel.send(ConversationEvent.OpenFile(bytes, media.fileName ?: "file", media.mimeType))
            }
        }
    }

    /**
     * Sends "typing" once while composing and "stopped" after a pause or on send. If the screen
     * closes mid-typing, the server expires it after the timeout.
     */
    private fun updateTyping(isTyping: Boolean) {
        stopTypingJob?.cancel()
        if (isTyping) {
            if (!isTypingSent) {
                isTypingSent = true
                viewModelScope.launch { chatRepository.setTyping(chatId, true) }
            }
            stopTypingJob = viewModelScope.launch {
                delay(TYPING_IDLE_MILLIS)
                updateTyping(false)
            }
        } else if (isTypingSent) {
            isTypingSent = false
            viewModelScope.launch { chatRepository.setTyping(chatId, false) }
        }
    }

    private companion object {
        const val TYPING_IDLE_MILLIS = 4_000L
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
