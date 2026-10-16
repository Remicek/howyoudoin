package com.devvalor.howyoudoing.chat.presentation.conversation

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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.chat.presentation.model.MessageUi
import com.devvalor.howyoudoing.core.designsystem.components.ChatAvatar
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.designsystem.theme.brandColor
import com.devvalor.howyoudoing.core.presentation.util.ObserveAsEvents
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ConversationRoot(
    onBack: () -> Unit,
    viewModel: ConversationViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is ConversationEvent.Error -> snackbarHostState.showSnackbar(event.message.asStringAsync())
        }
    }

    ConversationScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onAction = { action ->
            when (action) {
                ConversationAction.OnBackClick -> onBack()
                else -> viewModel.onAction(action)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    state: ConversationState,
    onAction: (ConversationAction) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onAction(ConversationAction.OnBackClick) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChatAvatar(name = state.chatName, network = state.network, size = 38.dp)
                        Spacer(Modifier.width(HowYouDoinSpacing.sm))
                        Column {
                            Text(state.chatName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            state.network?.let { network ->
                                Text(
                                    network.displayName,
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
                onLoadOlder = { onAction(ConversationAction.OnLoadOlder) },
                modifier = Modifier.weight(1f),
            )
            Composer(
                draft = state.draft,
                canSend = state.canSend,
                onDraftChange = { onAction(ConversationAction.OnDraftChange(it)) },
                onSend = { onAction(ConversationAction.OnSendClick) },
            )
        }
    }
}

@Composable
private fun MessageList(
    state: ConversationState,
    onLoadOlder: () -> Unit,
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
        if (isNearTop && state.hasMoreHistory) onLoadOlder()
    }
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.xs),
        contentPadding = PaddingValues(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(reversed, key = { it.id }) { message ->
            MessageBubble(message)
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

@Composable
private fun MessageBubble(message: MessageUi) {
    val isOutgoing = message.isOutgoing
    val bubbleColor = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val textColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isOutgoing) 18.dp else 4.dp,
        bottomEnd = if (isOutgoing) 4.dp else 18.dp,
    )

    Column(
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        message.senderName?.let { name ->
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = HowYouDoinSpacing.sm, top = HowYouDoinSpacing.xs, bottom = 2.dp),
            )
        }
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(bubbleColor, shape)
                .padding(horizontal = 14.dp, vertical = HowYouDoinSpacing.sm),
        ) {
            Text(
                message.body,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                fontStyle = if (message.type in setOf(MessageType.ENCRYPTED, MessageType.EMOTE, MessageType.UNSUPPORTED)) {
                    FontStyle.Italic
                } else {
                    FontStyle.Normal
                },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    message.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                )
                when (message.status) {
                    MessageStatus.SENDING -> StatusIcon(Icons.Rounded.Schedule, "Sending", textColor.copy(alpha = 0.7f))
                    MessageStatus.FAILED -> StatusIcon(Icons.Rounded.ErrorOutline, "Failed to send", MaterialTheme.colorScheme.error)
                    MessageStatus.SENT -> Unit
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, description: String, tint: Color) {
    Spacer(Modifier.width(HowYouDoinSpacing.xs))
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(12.dp))
}

@Composable
private fun Composer(
    draft: String,
    canSend: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = HowYouDoinSpacing.sm, vertical = HowYouDoinSpacing.sm),
        ) {
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Message") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(HowYouDoinSpacing.sm))
            FilledIconButton(
                onClick = onSend,
                enabled = canSend,
                shape = CircleShape,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
            }
        }
    }
}
