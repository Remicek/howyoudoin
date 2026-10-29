package com.devvalor.howyoudoing.chat.presentation.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.chat.domain.ChatContact
import com.devvalor.howyoudoing.chat.presentation.components.HomeBottomBar
import com.devvalor.howyoudoing.chat.presentation.conversation.components.AvatarImage
import com.devvalor.howyoudoing.chat.presentation.conversation.components.MediaResolver
import com.devvalor.howyoudoing.chat.presentation.components.HomeTab
import com.devvalor.howyoudoing.core.designsystem.components.ChatAvatar
import com.devvalor.howyoudoing.core.designsystem.components.NetworkBadge
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.presentation.util.ObserveAsEvents
import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.contacts_choose_network
import howyoudoin.feature.chat.presentation.generated.resources.contacts_no_networks
import howyoudoin.feature.chat.presentation.generated.resources.contacts_none
import howyoudoin.feature.chat.presentation.generated.resources.contacts_on_network
import howyoudoin.feature.chat.presentation.generated.resources.contacts_only_known
import howyoudoin.feature.chat.presentation.generated.resources.contacts_search
import howyoudoin.feature.chat.presentation.generated.resources.contacts_searching
import howyoudoin.feature.chat.presentation.generated.resources.contacts_title
import howyoudoin.feature.chat.presentation.generated.resources.inbox_all
import howyoudoin.feature.chat.presentation.generated.resources.inbox_clear_search
import howyoudoin.feature.chat.presentation.generated.resources.inbox_new_message
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The Contacts tab ([isNewMessage] false) or writing a new message: choosing the network, then the person. Picking
 * someone opens their chat, starting it where there isn't one yet.
 */
@Composable
fun ContactsRoot(
    isNewMessage: Boolean,
    onChatOpen: (chatId: String) -> Unit,
    onClose: () -> Unit,
    onChatsTab: () -> Unit = {},
    onSettingsTab: () -> Unit = {},
    viewModel: ContactsViewModel = koinViewModel(key = "contacts-$isNewMessage") { parametersOf(isNewMessage) }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.eventFlow) { event ->
        when (event) {
            is ContactsEvent.OpenChat -> onChatOpen(event.chatId)
            ContactsEvent.Close -> onClose()
        }
    }
    ContactsScreen(
        state = state,
        onAction = viewModel::onAction,
        resolver = viewModel::resolveMedia,
        onTab = { tab ->
            when (tab) {
                HomeTab.CHATS -> onChatsTab()
                HomeTab.SETTINGS -> onSettingsTab()
                HomeTab.CONTACTS -> Unit
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    state: ContactsState,
    onAction: (ContactsAction) -> Unit,
    onTab: (HomeTab) -> Unit = {},
    resolver: MediaResolver = { _, _ -> null },
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = if (state.isNewMessage) Res.string.inbox_new_message else Res.string.contacts_title
                    Text(stringResource(title), fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    if (state.isNewMessage) {
                        IconButton(onClick = { onAction(ContactsAction.OnBackClick) }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { if (!state.isNewMessage) HomeBottomBar(selected = HomeTab.CONTACTS, onSelect = onTab) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Unit
                state.networks.isEmpty() -> Message(stringResource(Res.string.contacts_no_networks))
                state.isPickingNetwork -> NetworkPicker(state.networks) { onAction(ContactsAction.OnNetworkSelect(it)) }
                else -> People(state, onAction, resolver)
            }
        }
    }
}

/** First step of a new message: which network to write on. */
@Composable
private fun NetworkPicker(networks: List<ChatNetwork>, onPick: (ChatNetwork) -> Unit) {
    Text(
        stringResource(Res.string.contacts_choose_network),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(HowYouDoinSpacing.md),
    )
    LazyColumn {
        items(networks, key = { it.name }) { network ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(network) }
                    .padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
            ) {
                NetworkBadge(network = network, size = 40.dp, borderColor = Color.Transparent)
                Spacer(Modifier.width(HowYouDoinSpacing.md))
                Text(network.displayName, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun People(state: ContactsState, onAction: (ContactsAction) -> Unit, resolver: MediaResolver) {
    SearchField(state.query) { onAction(ContactsAction.OnQueryChange(it)) }
    if (!state.isNewMessage && state.networks.size > 1) NetworkFilters(state, onAction)
    state.error?.let { error ->
        Text(
            error.asString(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.xs),
        )
    }
    val network = state.network?.displayName.orEmpty()
    state.searchError?.let { error ->
        Text(
            error.asString(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.xs),
        )
    }
    if (state.contacts.isEmpty() && state.found.isEmpty()) {
        if (state.isSearching) {
            Message(stringResource(Res.string.contacts_searching, network))
        } else {
            Message(stringResource(Res.string.contacts_none), stringResource(Res.string.contacts_only_known))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.contacts, key = { "${it.network}/${it.id}" }) { contact ->
            ContactRow(contact, showNetwork = !state.isNewMessage, resolver) {
                onAction(ContactsAction.OnContactClick(contact))
            }
        }
        if (state.found.isNotEmpty() || state.isSearching) {
            item(key = "found-header") {
                Text(
                    stringResource(
                        if (state.isSearching) Res.string.contacts_searching else Res.string.contacts_on_network,
                        network
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
                )
            }
        }
        items(state.found, key = { "found/${it.network}/${it.id}" }) { contact ->
            ContactRow(contact, showNetwork = false, resolver) { onAction(ContactsAction.OnContactClick(contact)) }
        }
    }
}

@Composable
private fun NetworkFilters(state: ContactsState, onAction: (ContactsAction) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.xs),
    ) {
        FilterChip(
            selected = state.network == null,
            onClick = { onAction(ContactsAction.OnNetworkSelect(null)) },
            label = { Text(stringResource(Res.string.inbox_all)) },
            shape = CircleShape,
        )
        state.networks.forEach { network ->
            FilterChip(
                selected = state.network == network,
                onClick = { onAction(ContactsAction.OnNetworkSelect(network)) },
                label = { Text(network.displayName) },
                leadingIcon = { NetworkBadge(network = network, size = 16.dp, borderColor = Color.Transparent) },
                shape = CircleShape,
            )
        }
    }
}

@Composable
private fun ContactRow(contact: ChatContact, showNetwork: Boolean, resolver: MediaResolver, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = HowYouDoinSpacing.md, vertical = 10.dp),
    ) {
        ChatAvatar(
            name = contact.name,
            network = contact.network.takeIf { showNetwork },
            size = 44.dp,
            image = contact.avatarUrl?.let { url -> { AvatarImage(url, resolver) } },
        )
        Spacer(Modifier.width(HowYouDoinSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium)
            contact.handle?.let { handle ->
                Text(
                    handle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 76.dp),
    )
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(Res.string.contacts_search)) },
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
private fun Message(title: String, detail: String? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(HowYouDoinSpacing.xl),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.height(HowYouDoinSpacing.sm))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
