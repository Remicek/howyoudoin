package com.devvalor.howyoudoing.chat.presentation.conversation

import com.devvalor.howyoudoing.core.domain.util.DataError
import howyoudoin.feature.chat.presentation.generated.resources.link_call_audio_instead
import com.devvalor.howyoudoing.chat.presentation.model.accountTag
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.core.domain.ondevice.AccountTags
import com.devvalor.howyoudoing.core.domain.connectivity.InternetConnection
import howyoudoin.feature.chat.presentation.generated.resources.offline_cant_send
import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.conversation_download_failed
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.CallLauncher
import com.devvalor.howyoudoing.chat.domain.CallManager
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.LinkCallLauncher
import com.devvalor.howyoudoing.chat.domain.LinkCallPreferences
import com.devvalor.howyoudoing.chat.domain.LinkCalls
import com.devvalor.howyoudoing.chat.domain.canCallByLink
import howyoudoin.feature.chat.presentation.generated.resources.link_call_failed
import howyoudoin.feature.chat.presentation.generated.resources.link_call_invite
import howyoudoin.feature.chat.presentation.generated.resources.link_call_invite_video
import org.jetbrains.compose.resources.getString
import com.devvalor.howyoudoing.chat.domain.NotificationPreferences
import com.devvalor.howyoudoing.chat.domain.OpenChatTracker
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.chat.presentation.model.toMessageUis
import com.devvalor.howyoudoing.chat.presentation.model.typingText
import com.devvalor.howyoudoing.chat.presentation.navigation.ChatRoutes
import com.devvalor.howyoudoing.chat.presentation.platform.PlayableVideo
import com.devvalor.howyoudoing.core.domain.util.onFailure
import com.devvalor.howyoudoing.core.domain.util.onSuccess
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

@Suppress("LongParameterList") // Built by Koin; each is something the chat screen uses
class ConversationViewModel(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val openChatTracker: OpenChatTracker,
    private val notificationPreferences: NotificationPreferences,
    private val callManager: CallManager,
    private val callLauncher: CallLauncher,
    private val internetConnection: InternetConnection,
    private val linkCalls: LinkCalls,
    private val linkCallPreferences: LinkCallPreferences,
    private val linkCallLauncher: LinkCallLauncher,
    private val accountTags: AccountTags,
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
    private var isLinkCallExplanationHidden = false

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
        internetConnection.isOnline
            .onEach { online -> _state.update { it.copy(isOffline = !online) } }
            .launchIn(viewModelScope)
        linkCallPreferences.observeExplanationHidden()
            .onEach { isLinkCallExplanationHidden = it }
            .launchIn(viewModelScope)
    }

    fun onAction(action: ConversationAction) {
        when (action) {
            is ConversationAction.OnDraftChange -> {
                _state.update { it.copy(draft = action.draft) }
                updateTyping(action.draft.isNotBlank())
            }

            ConversationAction.OnSendClick -> send()
            is ConversationAction.OnCallClick -> when {
                state.value.canCall -> callLauncher.call(chatId, action.video)
                state.value.canCallByLink -> askToCallByLink(
                    LinkCallPrompt(action.video, isCallBack = false, isGroup = state.value.isGroup)
                )
            }

            is ConversationAction.OnCallBackClick -> askToCallByLink(
                LinkCallPrompt(action.video, isCallBack = true, isGroup = state.value.isGroup)
            )
            is ConversationAction.OnLinkCallConfirm -> confirmLinkCall(action.dontShowAgain)
            ConversationAction.OnLinkCallDismiss -> _state.update { it.copy(linkCallPrompt = null) }
            ConversationAction.OnCallMinutesDismiss, ConversationAction.OnSeePremiumClick ->
                _state.update { it.copy(isOutOfCallMinutes = false) }
            ConversationAction.OnAcceptInviteClick -> answerInvite(accept = true)
            ConversationAction.OnDeclineInviteClick -> answerInvite(accept = false)
            ConversationAction.OnLoadOlder -> loadOlder()
            is ConversationAction.OnAttachmentPicked -> sendAttachment(action.attachment)
            is ConversationAction.OnVoiceRecorded -> sendVoice(action.attachment)
            is ConversationAction.OnAttachmentError -> viewModelScope.launch {
                eventChannel.send(ConversationEvent.Error(UiText.DynamicString(action.message)))
            }

            is ConversationAction.OnMediaClick -> openMedia(action.messageId)
            ConversationAction.OnCloseImage -> _state.update { it.copy(viewingImage = null) }
            ConversationAction.OnCloseVideo -> _state.update { it.copy(playingVideo = null) }
            is ConversationAction.OnQuoteClick -> jumpToMessage(action.messageId)
            ConversationAction.OnToggleMute -> viewModelScope.launch {
                notificationPreferences.setMuted(chatId, !state.value.isMuted)
            }

            ConversationAction.OnSearchClick,
            is ConversationAction.OnSearchQueryChange,
            ConversationAction.OnSearchOlder,
            ConversationAction.OnSearchNewer,
            ConversationAction.OnCloseSearch -> onSearchAction(action)

            ConversationAction.OnBackClick -> Unit
            else -> onMessageAction(action)
        }
    }

    /** What's done to one message from its actions sheet. */
    private fun onMessageAction(action: ConversationAction) {
        when (action) {
            is ConversationAction.OnRetryClick -> {
                _state.update { it.copy(actionsFor = null) }
                viewModelScope.launch { chatRepository.retryMessage(chatId, action.messageId) }
            }

            is ConversationAction.OnDiscardClick -> {
                _state.update { it.copy(actionsFor = null) }
                viewModelScope.launch { chatRepository.discardFailedMessage(chatId, action.messageId) }
            }

            is ConversationAction.OnMessageLongPress -> _state.update { state ->
                state.copy(actionsFor = state.messages.firstOrNull { it.id == action.messageId })
            }

            ConversationAction.OnDismissActions -> _state.update { it.copy(actionsFor = null) }
            is ConversationAction.OnReactionClick -> react(action.messageId, action.key)
            is ConversationAction.OnReplyClick -> _state.update { state ->
                state.copy(replyingTo = state.messages.firstOrNull { it.id == action.messageId }, actionsFor = null)
            }

            ConversationAction.OnCancelReply -> _state.update { it.copy(replyingTo = null) }
            is ConversationAction.OnEditClick -> _state.update { state ->
                val message = state.messages.firstOrNull { it.id == action.messageId }
                if (message == null) {
                    state.copy(actionsFor = null)
                } else {
                    state.copy(editing = message, draft = message.text, replyingTo = null, actionsFor = null)
                }
            }

            ConversationAction.OnCancelEdit -> _state.update { it.copy(editing = null, draft = "") }
            is ConversationAction.OnDeleteClick -> _state.update { state ->
                state.copy(
                    confirmingDelete = state.messages.firstOrNull { it.id == action.messageId },
                    actionsFor = null
                )
            }

            ConversationAction.OnDismissDelete -> _state.update { it.copy(confirmingDelete = null) }
            ConversationAction.OnConfirmDelete -> deleteForEveryone()
            is ConversationAction.OnCopyClick -> {
                val text = state.value.messages.firstOrNull { it.id == action.messageId }?.text
                _state.update { it.copy(actionsFor = null) }
                if (text != null) viewModelScope.launch { eventChannel.send(ConversationEvent.CopyToClipboard(text)) }
            }

            else -> Unit
        }
    }

    private fun onSearchAction(action: ConversationAction) {
        when (action) {
            ConversationAction.OnSearchClick -> _state.update { it.copy(searchQuery = "") }
            is ConversationAction.OnSearchQueryChange -> _state.update {
                it.copy(searchQuery = action.query).withSearchMatches()
            }

            ConversationAction.OnSearchOlder -> _state.update {
                it.copy(searchIndex = (it.searchIndex + 1).coerceAtMost((it.searchMatches.size - 1).coerceAtLeast(0)))
            }

            ConversationAction.OnSearchNewer -> _state.update {
                it.copy(searchIndex = (it.searchIndex - 1).coerceAtLeast(0))
            }

            ConversationAction.OnCloseSearch -> _state.update {
                it.copy(searchQuery = null, searchMatches = emptyList(), searchIndex = 0)
            }

            else -> Unit
        }
    }

    /** The chat's details; kept as they were while the chat isn't loaded (yet). */
    private fun ConversationState.withChat(chat: Chat?): ConversationState = if (chat == null) {
        this
    } else {
        copy(
            chatName = chat.name,
            avatarUrl = chat.avatarUrl ?: avatarUrl,
            network = chat.network,
            isGroup = chat.isGroup,
            isEncrypted = chat.isEncrypted,
            isOnDevice = chat.isOnDevice,
            canCall = callManager.canCall(chat),
            canCallByLink = linkCalls.isAvailable && chat.canCallByLink() && !chat.isInvite,
            isInvite = chat.isInvite
        )
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

    private fun answerInvite(accept: Boolean) {
        if (_state.value.isAnsweringInvite) return
        viewModelScope.launch {
            _state.update { it.copy(isAnsweringInvite = true) }
            val result = if (accept) chatRepository.acceptInvite(chatId) else chatRepository.declineInvite(chatId)
            _state.update { it.copy(isAnsweringInvite = false) }
            result
                .onSuccess { if (!accept) eventChannel.send(ConversationEvent.Close) }
                .onFailure { error -> eventChannel.send(ConversationEvent.Error(error.toUiText())) }
        }
    }

    private fun observeConversation() {
        combine(
            chatRepository.observeChat(chatId),
            chatRepository.observeMessages(chatId),
            chatRepository.hasMoreHistory(chatId),
            accountTags.observe()
        ) { chat, messages, hasMore, tags ->
            Triple(chat, messages, hasMore) to tags
        }
            .onEach { (loaded, tags) ->
                val (chat, messages, hasMore) = loaded
                _state.update {
                    it.withChat(chat).copy(
                        account = chat?.takeIf { c -> c.isOnDevice }
                            ?.let { c -> accountTag(AccountRef(c.network, c.accountSlot), tags) },
                        typingText = chat?.let { c -> typingText(c.typingNames, c.isGroup) },
                        messages = messages.toMessageUis(
                            showSenderNames = chat?.isGroup == true,
                            network = chat?.network
                        ),
                        hasMoreHistory = hasMore
                    ).let { updated -> if (updated.searchQuery != null) updated.withSearchMatches() else updated }
                }
                // Anything that arrives while the chat is open counts as read.
                if ((chat?.unreadCount ?: 0) > 0 && chat?.isInvite != true) {
                    chatRepository.markAsRead(chatId)
                }
            }
            .launchIn(viewModelScope)
    }

    /** Offline, nothing goes out: the user is reminded, and what they wrote stays in the composer. */
    private fun remindIfOffline(): Boolean {
        if (!state.value.isOffline) return false
        viewModelScope.launch {
            eventChannel.send(ConversationEvent.Error(UiText.Resource(Res.string.offline_cant_send)))
        }
        return true
    }

    private fun send() {
        val body = state.value.draft.trim()
        if (body.isEmpty() || remindIfOffline()) return
        state.value.editing?.let { editing ->
            _state.update { it.copy(draft = "", editing = null) }
            if (body != editing.text) {
                viewModelScope.launch {
                    chatRepository.editMessage(chatId, editing.id, body)
                        .onFailure { error -> eventChannel.send(ConversationEvent.Error(error.toUiText())) }
                }
            }
            return
        }
        val replyTo = state.value.replyingTo?.id
        _state.update { it.copy(draft = "", replyingTo = null) }
        updateTyping(false)
        viewModelScope.launch {
            chatRepository.sendMessage(chatId, body, replyTo)
                .onFailure { error -> eventChannel.send(ConversationEvent.Error(error.toUiText())) }
        }
    }

    /** Explains what a call through a link is first, unless the user asked not to be told again. */
    private fun askToCallByLink(prompt: LinkCallPrompt) {
        if (!state.value.canCallByLink || state.value.isStartingLinkCall) return
        if (isLinkCallExplanationHidden) {
            startLinkCall(prompt.video, prompt.isGroup)
        } else {
            _state.update { it.copy(linkCallPrompt = prompt) }
        }
    }

    private fun confirmLinkCall(dontShowAgain: Boolean) {
        val prompt = state.value.linkCallPrompt ?: return
        _state.update { it.copy(linkCallPrompt = null) }
        if (dontShowAgain) viewModelScope.launch { linkCallPreferences.setExplanationHidden(true) }
        startLinkCall(prompt.video, prompt.isGroup)
    }

    /**
     * Creates the call, sends its link in this chat, and opens it here; the other person (or, in a group, anyone in
     * it) joins from the link.
     */
    private fun startLinkCall(video: Boolean, group: Boolean) {
        if (remindIfOffline()) return
        _state.update { it.copy(isStartingLinkCall = true) }
        viewModelScope.launch {
            linkCalls.start(video, group)
                .onSuccess { call ->
                    val withVideo = video && !call.isAudioInstead
                    val invite = getString(
                        if (withVideo) Res.string.link_call_invite_video else Res.string.link_call_invite,
                        call.inviteUrl
                    )
                    chatRepository.sendMessage(chatId, invite, null)
                        .onSuccess {
                            linkCallLauncher.open(call.hostUrl)
                            if (call.isAudioInstead) {
                                val audioInstead = UiText.Resource(Res.string.link_call_audio_instead)
                                eventChannel.send(ConversationEvent.Error(audioInstead))
                            }
                        }
                        .onFailure { error -> eventChannel.send(ConversationEvent.Error(error.toUiText())) }
                }
                .onFailure { error ->
                    if (error == DataError.Remote.PAYMENT_REQUIRED) {
                        _state.update { it.copy(isOutOfCallMinutes = true) }
                    } else {
                        eventChannel.send(ConversationEvent.Error(UiText.Resource(Res.string.link_call_failed)))
                    }
                }
            _state.update { it.copy(isStartingLinkCall = false) }
        }
    }

    private fun deleteForEveryone() {
        val message = state.value.confirmingDelete ?: return
        _state.update { it.copy(confirmingDelete = null) }
        viewModelScope.launch {
            chatRepository.deleteMessage(chatId, message.id)
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
        if (remindIfOffline()) return
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
        if (remindIfOffline()) return
        _state.update { it.copy(isSendingAttachment = true) }
        viewModelScope.launch {
            chatRepository.sendAttachment(chatId, attachment, caption = null)
                .onFailure { error -> eventChannel.send(ConversationEvent.Error(error.toUiText())) }
            _state.update { it.copy(isSendingAttachment = false) }
        }
    }

    /** Media bytes for in-app playback (voice messages). */
    suspend fun loadMedia(url: String): ByteArray? = chatRepository.downloadMedia(url)

    /** Images open in the in-app viewer and videos play in the app; anything else is handed to another app. */
    private fun openMedia(messageId: String) {
        val message = state.value.messages.firstOrNull { it.id == messageId } ?: return
        val media = message.media ?: return
        if (message.type == MessageType.IMAGE || message.type == MessageType.STICKER) {
            _state.update { it.copy(viewingImage = message) }
            return
        }
        if (message.type == MessageType.VIDEO) {
            if (state.value.isLoadingVideo) return
            _state.update { it.copy(isLoadingVideo = true) }
            viewModelScope.launch {
                val bytes = chatRepository.downloadMedia(media.url)
                _state.update {
                    it.copy(
                        isLoadingVideo = false,
                        playingVideo = bytes?.let { b -> PlayableVideo(b, media.mimeType, media.fileName) }
                    )
                }
                if (bytes == null) {
                    eventChannel.send(ConversationEvent.Error(UiText.Resource(Res.string.conversation_download_failed)))
                }
            }
            return
        }
        viewModelScope.launch {
            val bytes = chatRepository.downloadMedia(media.url)
            if (bytes == null) {
                eventChannel.send(ConversationEvent.Error(UiText.Resource(Res.string.conversation_download_failed)))
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
        const val MAX_PAGES_TO_ORIGINAL = 20
        const val PAGE_ARRIVAL_CHECKS = 10
        const val PAGE_ARRIVAL_CHECK_MILLIS = 100L
        const val HIGHLIGHT_MILLIS = 1_800L
    }

    /**
     * Brings a reply's original message into view and highlights it for a moment, loading older messages until it
     * is there (or the chat's history ends).
     */
    private fun jumpToMessage(messageId: String) {
        viewModelScope.launch {
            fun isLoaded() = _state.value.messages.any { it.id == messageId }
            var pages = 0
            while (!isLoaded() && _state.value.hasMoreHistory && pages < MAX_PAGES_TO_ORIGINAL) {
                pages++
                chatRepository.loadOlderMessages(chatId)
                // The loaded page reaches the state through the chat's message flow
                var checks = 0
                while (!isLoaded() && checks < PAGE_ARRIVAL_CHECKS) {
                    delay(PAGE_ARRIVAL_CHECK_MILLIS)
                    checks++
                }
            }
            if (!isLoaded()) return@launch
            _state.update { it.copy(highlightedMessageId = messageId) }
            delay(HIGHLIGHT_MILLIS)
            _state.update { if (it.highlightedMessageId == messageId) it.copy(highlightedMessageId = null) else it }
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
