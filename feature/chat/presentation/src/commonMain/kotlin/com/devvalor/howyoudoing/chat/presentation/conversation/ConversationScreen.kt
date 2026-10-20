package com.devvalor.howyoudoing.chat.presentation.conversation

import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.chat.presentation.model.MessageUi
import com.devvalor.howyoudoing.core.designsystem.components.ChatAvatar
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.designsystem.theme.brandColor
import com.devvalor.howyoudoing.core.presentation.util.ObserveAsEvents
import com.devvalor.howyoudoing.chat.presentation.conversation.components.AvatarImage
import com.devvalor.howyoudoing.chat.presentation.conversation.components.Composer
import com.devvalor.howyoudoing.chat.presentation.conversation.components.ImageViewer
import com.devvalor.howyoudoing.chat.presentation.conversation.components.MediaResolver
import com.devvalor.howyoudoing.chat.presentation.conversation.components.MessageActionsSheet
import com.devvalor.howyoudoing.chat.presentation.conversation.components.MessageBubble
import com.devvalor.howyoudoing.chat.presentation.platform.isAttachmentPickerSupported
import com.devvalor.howyoudoing.chat.presentation.platform.rememberAttachmentPicker
import com.devvalor.howyoudoing.chat.presentation.platform.rememberFileOpener
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.runtime.CompositionLocalProvider
import com.devvalor.howyoudoing.chat.presentation.conversation.components.LocalVoicePlayback
import com.devvalor.howyoudoing.chat.presentation.conversation.components.VoiceMessagesUi
import com.devvalor.howyoudoing.chat.presentation.conversation.components.rememberVoiceMessages
import com.devvalor.howyoudoing.chat.presentation.platform.VoiceFormat
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

@Composable
fun ConversationRoot(
    onBack: () -> Unit,
    viewModel: ConversationViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val openFile = rememberFileOpener()
    val pickAttachment = rememberAttachmentPicker(
        onPicked = { viewModel.onAction(ConversationAction.OnAttachmentPicked(it)) },
        onError = { viewModel.onAction(ConversationAction.OnAttachmentError(it)) },
    )

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is ConversationEvent.Error -> snackbarHostState.showSnackbar(event.message.asStringAsync())
            is ConversationEvent.CopyToClipboard -> {
                clipboard.setText(AnnotatedString(event.text))
                snackbarHostState.showSnackbar("Copied")
            }
            is ConversationEvent.OpenFile -> openFile(event.bytes, event.fileName, event.mimeType)
        }
    }

    val voice = rememberVoiceMessages(
        format = voiceFormatFor(state.network, state.isOnDevice),
        loadMedia = viewModel::loadMedia,
        onRecorded = { viewModel.onAction(ConversationAction.OnVoiceRecorded(it)) },
        onError = { message -> viewModel.onAction(ConversationAction.OnAttachmentError(message)) },
    )

    CompositionLocalProvider(LocalVoicePlayback provides voice.playback) {
        ConversationScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            resolver = viewModel::resolveMedia,
            canAttach = isAttachmentPickerSupported,
            onAttach = pickAttachment,
            voice = voice,
            onAction = { action ->
                when (action) {
                    ConversationAction.OnBackClick -> onBack()
                    else -> viewModel.onAction(action)
                }
            }
        )
    }
}

/**
 * The voice note format the chat's network expects, or null where voice messages can't be sent from here.
 */
private fun voiceFormatFor(network: ChatNetwork?, isOnDevice: Boolean): VoiceFormat? = when {
    // Through the server, bridges convert Matrix voice messages (Ogg/Opus) for their network
    !isOnDevice -> VoiceFormat.OGG_OPUS
    network == ChatNetwork.WHATSAPP || network == ChatNetwork.TELEGRAM -> VoiceFormat.OGG_OPUS
    network == ChatNetwork.SIGNAL -> VoiceFormat.AAC_ADTS
    network == ChatNetwork.MESSENGER || network == ChatNetwork.INSTAGRAM -> VoiceFormat.M4A_AAC
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    state: ConversationState,
    onAction: (ConversationAction) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    resolver: MediaResolver = { _, _ -> null },
    canAttach: Boolean = false,
    onAttach: () -> Unit = {},
    voice: VoiceMessagesUi? = null,
) {
    Scaffold(
        topBar = {
            if (state.searchQuery != null) {
                SearchBar(state, onAction)
                return@Scaffold
            }
            TopAppBar(
                actions = {
                    IconButton(onClick = { onAction(ConversationAction.OnSearchClick) }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search in chat")
                    }
                    IconButton(onClick = { onAction(ConversationAction.OnToggleMute) }) {
                        Icon(
                            if (state.isMuted) Icons.Rounded.NotificationsOff else Icons.Rounded.Notifications,
                            contentDescription = if (state.isMuted) "Unmute notifications" else "Mute notifications",
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onAction(ConversationAction.OnBackClick) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChatAvatar(
                            name = state.chatName,
                            network = state.network,
                            size = 38.dp,
                            image = state.avatarUrl?.let { url -> { AvatarImage(url, resolver) } },
                        )
                        Spacer(Modifier.width(HowYouDoinSpacing.sm))
                        Column {
                            Text(state.chatName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            if (state.typingText != null) {
                                Text(
                                    state.typingText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else state.network?.let { network ->
                                Text(
                                    listOfNotNull(
                                        network.displayName,
                                        "end-to-end encrypted".takeIf { state.isEncrypted },
                                        "on this device".takeIf { state.isOnDevice },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = network.brandColor,
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The composer handles the bottom (navigation bar / keyboard) inset itself
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            MessageList(
                state = state,
                resolver = resolver,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )
            Composer(
                draft = state.draft,
                canSend = state.canSend,
                replyingTo = state.replyingTo,
                isSendingAttachment = state.isSendingAttachment,
                canAttach = canAttach,
                onDraftChange = { onAction(ConversationAction.OnDraftChange(it)) },
                onSend = { onAction(ConversationAction.OnSendClick) },
                onAttach = onAttach,
                onCancelReply = { onAction(ConversationAction.OnCancelReply) },
                editing = state.editing,
                onCancelEdit = { onAction(ConversationAction.OnCancelEdit) },
                chatName = state.chatName.takeIf { !state.isGroup },
                canRecord = voice?.canRecord == true,
                recording = voice?.recording,
                onRecordStart = { voice?.start?.invoke() },
                onRecordCancel = { voice?.cancel?.invoke() },
                onRecordSend = { voice?.send?.invoke() },
            )
        }
    }

    state.actionsFor?.let { message ->
        MessageActionsSheet(
            message = message,
            onReaction = { onAction(ConversationAction.OnReactionClick(message.id, it)) },
            onReply = { onAction(ConversationAction.OnReplyClick(message.id)) },
            onCopy = { onAction(ConversationAction.OnCopyClick(message.id)) },
            onDismiss = { onAction(ConversationAction.OnDismissActions) },
            onEdit = if (message.canEdit()) ({ onAction(ConversationAction.OnEditClick(message.id)) }) else null,
            onDelete = if (message.canDelete()) ({ onAction(ConversationAction.OnDeleteClick(message.id)) }) else null,
        )
    }
    state.confirmingDelete?.let {
        AlertDialog(
            onDismissRequest = { onAction(ConversationAction.OnDismissDelete) },
            title = { Text("Delete for everyone?") },
            text = { Text("The message is removed for everyone in the chat. Some apps show that a message was deleted.") },
            confirmButton = {
                TextButton(onClick = { onAction(ConversationAction.OnConfirmDelete) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { onAction(ConversationAction.OnDismissDelete) }) { Text("Cancel") } },
        )
    }
    state.viewingImage?.media?.let { media ->
        ImageViewer(media = media, resolver = resolver, onClose = { onAction(ConversationAction.OnCloseImage) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(state: ConversationState, onAction: (ConversationAction) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = { onAction(ConversationAction.OnCloseSearch) }) {
                Icon(Icons.Rounded.Close, contentDescription = "Close search")
            }
        },
        title = {
            TextField(
                value = state.searchQuery.orEmpty(),
                onValueChange = { onAction(ConversationAction.OnSearchQueryChange(it)) },
                placeholder = { Text("Search in chat") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        actions = {
            if (!state.searchQuery.isNullOrBlank()) {
                Text(
                    if (state.searchMatches.isEmpty()) "0" else "${state.searchIndex + 1}/${state.searchMatches.size}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(
                onClick = { onAction(ConversationAction.OnSearchOlder) },
                enabled = state.searchIndex < state.searchMatches.size - 1,
            ) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Older match")
            }
            IconButton(
                onClick = { onAction(ConversationAction.OnSearchNewer) },
                enabled = state.searchIndex > 0,
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Newer match")
            }
        },
    )
}

@Composable
private fun MessageList(
    state: ConversationState,
    resolver: MediaResolver,
    onAction: (ConversationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // reverseLayout: index 0 is the newest message, so the end of the list is the oldest.
    val reversed = remember(state.messages) { state.messages.asReversed() }

    val isNearTop by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(isNearTop, state.hasMoreHistory, state.messages.size) {
        if (isNearTop && state.hasMoreHistory) onAction(ConversationAction.OnLoadOlder)
    }
    // Stay on the newest message (a chat opens at the bottom, even while its messages arrive in several batches)
    // until the user scrolls up themselves; scrolling back down to the newest one turns it on again.
    var followNewest by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followNewest = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && !listState.isScrollInProgress }
            .collect { atNewest -> if (atNewest) followNewest = true }
    }
    var shownCount by remember { mutableStateOf(0) }
    LaunchedEffect(state.messages.lastOrNull()?.id, state.messages.size) {
        val previousCount = shownCount
        shownCount = state.messages.size
        if (!followNewest || state.messages.isEmpty()) return@LaunchedEffect
        // One new message slides in; a batch (opening the chat, history arriving) jumps without animation
        if (state.messages.size - previousCount == 1) listState.animateScrollToItem(0) else listState.scrollToItem(0)
    }

    // Searching: bring the current match into view
    LaunchedEffect(state.currentMatchId) {
        val index = reversed.indexOfFirst { it.id == state.currentMatchId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.xs),
        contentPadding = PaddingValues(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(reversed, key = { it.id }) { message ->
            val isMatch = message.id == state.currentMatchId
            Column(
                modifier = if (isMatch) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                } else Modifier
            ) {
                message.dayHeader?.let { DayHeader(it) }
                MessageBubble(
                    message = message,
                    resolver = resolver,
                    onLongPress = { onAction(ConversationAction.OnMessageLongPress(message.id)) },
                    onMediaClick = { onAction(ConversationAction.OnMediaClick(message.id)) },
                    onReactionClick = { onAction(ConversationAction.OnReactionClick(message.id, it)) },
                )
            }
        }
        if (!state.hasMoreHistory && !state.isLoadingOlder) {
            item(key = "history-start") { HistoryStartNote(state) }
        }
        if (state.isLoadingOlder) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(HowYouDoinSpacing.sm), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/**
 * Shown above the oldest message we have. Messenger's end-to-end encrypted 1:1 chats only keep their
 * history on the user's own devices, so a newly linked bridge can't fetch it; new messages do arrive.
 */
@Composable
private fun HistoryStartNote(state: ConversationState) {
    val text = if (state.isOnDevice) {
        // On-device chats load history from the network (Messenger's backup, WhatsApp's phone) as far as it goes
        "This is the start of the history available on this device."
    } else if (state.isEncrypted) {
        "Older messages in end-to-end encrypted ${state.network?.displayName ?: ""} chats stay on your other devices and can't be " +
            "transferred. New messages will appear here."
    } else {
        "This is the start of the history available in HowYouDoin."
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = HowYouDoinSpacing.lg, vertical = HowYouDoinSpacing.md),
    )
}

@Composable
private fun DayHeader(label: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(vertical = HowYouDoinSpacing.sm)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** Our own sent text messages can be edited. */
private fun MessageUi.canEdit(): Boolean =
    isOutgoing && type == MessageType.TEXT && status == MessageStatus.SENT && !id.startsWith("pending-") && text.isNotBlank()

/** Our own sent messages can be deleted for everyone (not ones already unsent). */
private fun MessageUi.canDelete(): Boolean =
    isOutgoing && type != MessageType.NOTICE && status == MessageStatus.SENT && !id.startsWith("pending-")
