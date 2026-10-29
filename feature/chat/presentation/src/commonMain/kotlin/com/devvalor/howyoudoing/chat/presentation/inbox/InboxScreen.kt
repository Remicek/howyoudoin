package com.devvalor.howyoudoing.chat.presentation.inbox

import howyoudoin.feature.chat.presentation.generated.resources.inbox_more_filters
import howyoudoin.feature.chat.presentation.generated.resources.inbox_search
import howyoudoin.feature.chat.presentation.generated.resources.inbox_add_network
import howyoudoin.feature.chat.presentation.generated.resources.inbox_new_message
import howyoudoin.feature.chat.presentation.generated.resources.inbox_new
import howyoudoin.feature.chat.presentation.generated.resources.home_chats
import com.devvalor.howyoudoing.chat.presentation.components.HomeTab
import com.devvalor.howyoudoing.chat.presentation.components.HomeBottomBar
import com.devvalor.howyoudoing.core.designsystem.theme.Wordmark
import com.devvalor.howyoudoing.core.designsystem.theme.AppLogo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import com.devvalor.howyoudoing.chat.presentation.model.text
import com.devvalor.howyoudoing.chat.presentation.model.title
import howyoudoin.feature.chat.presentation.generated.resources.inbox_connecting
import howyoudoin.feature.chat.presentation.generated.resources.inbox_offline
import howyoudoin.feature.chat.presentation.generated.resources.inbox_no_match
import howyoudoin.feature.chat.presentation.generated.resources.inbox_empty
import howyoudoin.feature.chat.presentation.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import howyoudoin.feature.chat.presentation.generated.resources.inbox_all
import howyoudoin.feature.chat.presentation.generated.resources.inbox_clear_search
import howyoudoin.feature.chat.presentation.generated.resources.inbox_connect_a_network
import howyoudoin.feature.chat.presentation.generated.resources.inbox_connect_whatsapp_messenger_instagram_telegram
import howyoudoin.feature.chat.presentation.generated.resources.inbox_connected_networks
import howyoudoin.feature.chat.presentation.generated.resources.inbox_connected_on_this_device
import howyoudoin.feature.chat.presentation.generated.resources.inbox_dismiss
import howyoudoin.feature.chat.presentation.generated.resources.inbox_end_to_end_encrypted
import howyoudoin.feature.chat.presentation.generated.resources.inbox_group
import howyoudoin.feature.chat.presentation.generated.resources.inbox_how_connecting_works
import howyoudoin.feature.chat.presentation.generated.resources.inbox_inbox
import howyoudoin.feature.chat.presentation.generated.resources.inbox_log_out
import howyoudoin.feature.chat.presentation.generated.resources.inbox_more
import howyoudoin.feature.chat.presentation.generated.resources.inbox_muted
import howyoudoin.feature.chat.presentation.generated.resources.inbox_search_chats
import howyoudoin.feature.chat.presentation.generated.resources.inbox_settings
import howyoudoin.feature.chat.presentation.generated.resources.inbox_unread
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Surface
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionProblem
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
import androidx.compose.material3.TextButton
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
    onContactsClick: () -> Unit = {},
    onNewMessageClick: () -> Unit = {},
    onGuideClick: () -> Unit = {},
    banner: @Composable (encryptedNetworks: Set<ChatNetwork>) -> Unit = {},
    viewModel: InboxViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    InboxScreen(
        state = state,
        banner = {
            banner(state.encryptedNetworks)
            MatrixKeysBannerRoot(onNetworksClick = onNetworksClick)
        },
        resolver = viewModel::resolveMedia,
        onAction = { action ->
            when (action) {
                is InboxAction.OnChatClick -> onChatClick(action.chatId)
                InboxAction.OnNetworksClick -> onNetworksClick()
                InboxAction.OnSettingsClick -> onSettingsClick()
                InboxAction.OnContactsClick -> onContactsClick()
                InboxAction.OnNewMessageClick -> onNewMessageClick()
                InboxAction.OnGuideClick -> onGuideClick()
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
    var isSearchOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            InboxTopBar(
                onNewMessage = { onAction(InboxAction.OnNewMessageClick) },
                onAddNetwork = { onAction(InboxAction.OnNetworksClick) },
                onSearch = {
                    if (isSearchOpen) onAction(InboxAction.OnQueryChange(""))
                    isSearchOpen = !isSearchOpen
                },
            )
        },
        bottomBar = {
            HomeBottomBar(
                selected = HomeTab.CHATS,
                unread = state.totalUnread,
                onSelect = { tab ->
                    when (tab) {
                        HomeTab.CONTACTS -> onAction(InboxAction.OnContactsClick)
                        HomeTab.SETTINGS -> onAction(InboxAction.OnSettingsClick)
                        HomeTab.CHATS -> Unit
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.xs),
            ) {
                Text(
                    stringResource(Res.string.home_chats),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(HowYouDoinSpacing.sm))
                SyncStatusLabel(state.syncState)
            }
            if (isSearchOpen || state.query.isNotEmpty()) {
                SearchField(
                    query = state.query,
                    onQueryChange = { onAction(InboxAction.OnQueryChange(it)) },
                )
            }
            FilterRow(state = state, onAction = onAction)
            ConnectionProblems(
                problems = state.connectionProblems,
                onClick = { onAction(InboxAction.OnNetworksClick) },
                onDismiss = { onAction(InboxAction.OnDismissProblem(it)) },
            )
            banner()

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.chats.isEmpty() -> EmptyInbox(
                    isFiltered = state.query.isNotBlank() || state.filter != InboxFilter.All,
                    onNetworksClick = { onAction(InboxAction.OnNetworksClick) },
                    onGuideClick = { onAction(InboxAction.OnGuideClick) },
                )

                else -> PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { onAction(InboxAction.OnRefresh) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val listState = rememberLazyListState()
                    StayAtTop(listState, topChatId = state.chats.firstOrNull()?.id)
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = HowYouDoinSpacing.lg),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.chats, key = { it.id }) { chat ->
                            ChatRow(
                                chat = chat,
                                resolver = resolver,
                                onClick = { onAction(InboxAction.OnChatClick(chat.id)) })
                        }
                    }
                }
            }
        }
    }
}

/** The logo and name, then "+" (a new message, or another network) and search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxTopBar(onNewMessage: () -> Unit, onAddNetwork: () -> Unit, onSearch: () -> Unit) {
    var isNewMenuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(AppLogo, contentDescription = null, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(6.dp))
                Wordmark()
            }
        },
        actions = {
            Box {
                IconButton(onClick = { isNewMenuOpen = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(Res.string.inbox_new))
                }
                DropdownMenu(expanded = isNewMenuOpen, onDismissRequest = { isNewMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.inbox_new_message)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            isNewMenuOpen = false
                            onNewMessage()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.inbox_add_network)) },
                        leadingIcon = { Icon(Icons.Rounded.Hub, contentDescription = null) },
                        onClick = {
                            isNewMenuOpen = false
                            onAddNetwork()
                        },
                    )
                }
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Rounded.Search, contentDescription = stringResource(Res.string.inbox_search))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** One card per network that stopped working; tapping opens Networks to fix it. */
@Composable
private fun ConnectionProblems(
    problems: List<ConnectionProblem>,
    onClick: () -> Unit,
    onDismiss: (ChatNetwork) -> Unit
) {
    problems.forEach { problem ->
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    start = HowYouDoinSpacing.md,
                    top = HowYouDoinSpacing.sm,
                    bottom = HowYouDoinSpacing.sm
                ),
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                Column(Modifier.weight(1f).padding(horizontal = HowYouDoinSpacing.md)) {
                    Text(
                        problem.title.asString(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(problem.text.asString(), style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
                // A logged-out network can be ignored; the others clear themselves once they reconnect
                if (problem.kind == ConnectionProblem.Kind.LOGGED_OUT) {
                    IconButton(onClick = { onDismiss(problem.network) }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.inbox_dismiss))
                    }
                } else {
                    Spacer(Modifier.width(HowYouDoinSpacing.md))
                }
            }
        }
    }
}

@Composable
private fun SyncStatusLabel(syncState: SyncState) {
    val text = when (syncState) {
        SyncState.CONNECTING -> stringResource(Res.string.inbox_connecting)
        SyncState.OFFLINE -> stringResource(Res.string.inbox_offline)
        SyncState.IDLE, SyncState.SYNCED -> return
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(Res.string.inbox_search_chats)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.inbox_clear_search))
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
    var isMoreOpen by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.xs),
    ) {
        FilterChip(
            selected = state.filter == InboxFilter.All,
            onClick = { onAction(InboxAction.OnFilterSelect(InboxFilter.All)) },
            label = { Text(stringResource(Res.string.inbox_all)) },
            shape = CircleShape,
        )
        state.networksInInbox.forEach { network ->
            val filter = InboxFilter.Network(network)
            NetworkFilter(network, isSelected = state.filter == filter) {
                onAction(InboxAction.OnFilterSelect(if (state.filter == filter) InboxFilter.All else filter))
            }
        }
        Box {
            FilterChip(
                selected = state.filter == InboxFilter.Unread,
                onClick = { isMoreOpen = true },
                label = { Icon(Icons.Rounded.MoreHoriz, stringResource(Res.string.inbox_more_filters)) },
                shape = CircleShape,
            )
            DropdownMenu(expanded = isMoreOpen, onDismissRequest = { isMoreOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.inbox_unread)) },
                    trailingIcon = {
                        if (state.filter == InboxFilter.Unread) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = {
                        isMoreOpen = false
                        val unread = state.filter == InboxFilter.Unread
                        onAction(InboxAction.OnFilterSelect(if (unread) InboxFilter.All else InboxFilter.Unread))
                    },
                )
            }
        }
    }
}

/** A network's round icon; selected, it gets a ring in the primary colour. */
@Composable
private fun NetworkFilter(network: ChatNetwork, isSelected: Boolean, onClick: () -> Unit) {
    val ring = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(NETWORK_FILTER_SIZE)
            .border(2.dp, ring, CircleShape)
            .clip(CircleShape)
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = network.displayName },
    ) {
        NetworkBadge(network = network, size = NETWORK_FILTER_SIZE - 8.dp, borderColor = Color.Transparent)
    }
}

private val NETWORK_FILTER_SIZE = 40.dp

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
            image = chat.avatarUrl?.let { url -> { AvatarImage(url, resolver) } },
        )
        Spacer(Modifier.width(HowYouDoinSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NetworkBadge(network = chat.network, size = 16.dp, borderColor = Color.Transparent)
                Spacer(Modifier.width(6.dp))
                if (chat.isGroup) {
                    Icon(
                        Icons.Rounded.Groups,
                        contentDescription = stringResource(Res.string.inbox_group),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(HowYouDoinSpacing.xs))
                }
                if (chat.isOnDevice) {
                    Icon(
                        Icons.Rounded.PhoneAndroid,
                        contentDescription = stringResource(Res.string.inbox_connected_on_this_device),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(HowYouDoinSpacing.xs))
                }
                if (chat.isEncrypted) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = stringResource(Res.string.inbox_end_to_end_encrypted),
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
                        contentDescription = stringResource(Res.string.inbox_muted),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(HowYouDoinSpacing.xs))
                }
                Text(
                    chat.timestamp.asString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUnread) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (chat.typingText ?: chat.preview).asString(),
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
private fun EmptyInbox(isFiltered: Boolean, onNetworksClick: () -> Unit, onGuideClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(HowYouDoinSpacing.xl),
    ) {
        Text(
            stringResource(if (isFiltered) Res.string.inbox_no_match else Res.string.inbox_empty),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(HowYouDoinSpacing.sm))
        if (!isFiltered) {
            Text(
                stringResource(Res.string.inbox_connect_whatsapp_messenger_instagram_telegram),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(HowYouDoinSpacing.lg))
            FilledTonalButton(onClick = onNetworksClick) {
                Text(stringResource(Res.string.inbox_connect_a_network))
            }
            TextButton(onClick = onGuideClick) {
                Text(stringResource(Res.string.inbox_how_connecting_works))
            }
        }
    }
}

/**
 * A list keeps showing the item it showed when others are added above it; for the inbox that hides new chats (and
 * chats that load a moment later) above the screen. Whoever was at the top stays at the top.
 */
@Composable
private fun StayAtTop(listState: LazyListState, topChatId: String?) {
    var previousTopId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(topChatId) {
        val wasAtTop = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key == previousTopId &&
                listState.firstVisibleItemScrollOffset == 0
        if (previousTopId != null && wasAtTop) listState.scrollToItem(0)
        previousTopId = topChatId
    }
}
