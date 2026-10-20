package com.devvalor.howyoudoing.chat.presentation.inbox

import com.devvalor.howyoudoing.chat.presentation.conversation.components.AvatarImage
import com.devvalor.howyoudoing.chat.presentation.conversation.components.MediaResolver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.chat.presentation.model.ChatUi
import com.devvalor.howyoudoing.core.designsystem.components.ChatAvatar
import com.devvalor.howyoudoing.core.designsystem.components.NetworkBadge
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun InboxRoot(
    onChatClick: (chatId: String) -> Unit,
    onNetworksClick: () -> Unit,
    onSettingsClick: () -> Unit,
    banner: @Composable (encryptedNetworks: Set<ChatNetwork>) -> Unit = {},
    viewModel: InboxViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    InboxScreen(
        state = state,
        banner = { banner(state.encryptedNetworks) },
        resolver = viewModel::resolveMedia,
        onAction = { action ->
            when (action) {
                is InboxAction.OnChatClick -> onChatClick(action.chatId)
                InboxAction.OnNetworksClick -> onNetworksClick()
                InboxAction.OnSettingsClick -> onSettingsClick()
                else -> viewModel.onAction(action)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    state: InboxState,
    onAction: (InboxAction) -> Unit,
    /** Slot for other features' prompts (e.g. restoring encrypted history). */
    banner: @Composable () -> Unit = {},
    resolver: MediaResolver = { _, _ -> null },
) {
    var isMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Inbox", fontWeight = FontWeight.Bold)
                        SyncStatusLabel(state.syncState)
                    }
                },
                actions = {
                    IconButton(onClick = { onAction(InboxAction.OnNetworksClick) }) {
                        Icon(Icons.Rounded.Hub, contentDescription = "Connected networks")
                    }
                    Box {
                        IconButton(onClick = { isMenuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = isMenuOpen, onDismissRequest = { isMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                onClick = {
                                    isMenuOpen = false
                                    onAction(InboxAction.OnSettingsClick)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Log out") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
                                onClick = {
                                    isMenuOpen = false
                                    onAction(InboxAction.OnLogoutClick)
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                query = state.query,
                onQueryChange = { onAction(InboxAction.OnQueryChange(it)) },
            )
            FilterRow(state = state, onAction = onAction)
            banner()

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.chats.isEmpty() -> EmptyInbox(
                    isFiltered = state.query.isNotBlank() || state.filter != InboxFilter.All,
                    onNetworksClick = { onAction(InboxAction.OnNetworksClick) },
                )
                else -> PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { onAction(InboxAction.OnRefresh) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = HowYouDoinSpacing.lg),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.chats, key = { it.id }) { chat ->
                            ChatRow(chat = chat, resolver = resolver, onClick = { onAction(InboxAction.OnChatClick(chat.id)) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStatusLabel(syncState: SyncState) {
    val text = when (syncState) {
        SyncState.CONNECTING -> "Connecting…"
        SyncState.OFFLINE -> "Offline, retrying…"
        SyncState.IDLE, SyncState.SYNCED -> return
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search chats") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                }
            }
        } else null,
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.xs),
    )
}

@Composable
private fun FilterRow(state: InboxState, onAction: (InboxAction) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.xs),
    ) {
        FilterChip(
            selected = state.filter == InboxFilter.All,
            onClick = { onAction(InboxAction.OnFilterSelect(InboxFilter.All)) },
            label = { Text("All") },
        )
        FilterChip(
            selected = state.filter == InboxFilter.Unread,
            onClick = { onAction(InboxAction.OnFilterSelect(InboxFilter.Unread)) },
            label = { Text("Unread") },
        )
        state.networksInInbox.forEach { network ->
            val filter = InboxFilter.Network(network)
            FilterChip(
                selected = state.filter == filter,
                onClick = { onAction(InboxAction.OnFilterSelect(filter)) },
                label = { Text(network.displayName) },
                leadingIcon = { NetworkBadge(network = network, size = 16.dp, borderColor = Color.Transparent) },
            )
        }
    }
}

@Composable
private fun ChatRow(chat: ChatUi, resolver: MediaResolver, onClick: () -> Unit) {
    val isUnread = chat.unreadCount > 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = HowYouDoinSpacing.md, vertical = 10.dp),
    ) {
        ChatAvatar(
            name = chat.name,
            network = chat.network,
            image = chat.avatarUrl?.let { url -> { AvatarImage(url, resolver) } },
        )
        Spacer(Modifier.width(HowYouDoinSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.isGroup) {
                    Icon(
                        Icons.Rounded.Groups,
                        contentDescription = "Group",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(HowYouDoinSpacing.xs))
                }
                if (chat.isOnDevice) {
                    Icon(
                        Icons.Rounded.PhoneAndroid,
                        contentDescription = "Connected on this device",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(HowYouDoinSpacing.xs))
                }
                if (chat.isEncrypted) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = "End-to-end encrypted",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(HowYouDoinSpacing.xs))
                }
                Text(
                    chat.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.isMuted) {
                    Icon(
                        Icons.Rounded.NotificationsOff,
                        contentDescription = "Muted",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(HowYouDoinSpacing.xs))
                }
                Text(
                    chat.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.typingText ?: chat.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        chat.typingText != null -> MaterialTheme.colorScheme.primary
                        isUnread -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isUnread) {
                    Spacer(Modifier.width(HowYouDoinSpacing.sm))
                    UnreadBadge(chat.unreadCount)
                }
            }
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 84.dp),
    )
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(20.dp)
            .widthIn(min = 20.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 6.dp),
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EmptyInbox(isFiltered: Boolean, onNetworksClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(HowYouDoinSpacing.xl),
    ) {
        Text(
            if (isFiltered) "No chats match" else "No chats yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(HowYouDoinSpacing.sm))
        if (!isFiltered) {
            Text(
                "Connect WhatsApp, Messenger or Instagram and your conversations will show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(HowYouDoinSpacing.lg))
            FilledTonalButton(onClick = onNetworksClick) {
                Text("Connect a network")
            }
        }
    }
}
