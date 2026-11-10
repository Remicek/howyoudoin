package com.devvalor.howyoudoing.networks.presentation.list

import howyoudoin.feature.networks.presentation.generated.resources.networks_premium_not_now
import howyoudoin.feature.networks.presentation.generated.resources.networks_premium_see
import howyoudoin.feature.networks.presentation.generated.resources.networks_premium_accounts_body
import howyoudoin.feature.networks.presentation.generated.resources.networks_premium_accounts_title
import com.devvalor.howyoudoing.core.domain.ondevice.MAX_ACCOUNTS_PER_NETWORK
import androidx.compose.material.icons.rounded.WorkspacePremium
import org.koin.compose.koinInject
import com.devvalor.howyoudoing.core.domain.premium.Premium
import howyoudoin.feature.networks.presentation.generated.resources.networks_fix_title
import howyoudoin.feature.networks.presentation.generated.resources.networks_fix_reason
import howyoudoin.feature.networks.presentation.generated.resources.networks_fix_intro
import howyoudoin.feature.networks.presentation.generated.resources.networks_fix_step_disconnect
import howyoudoin.feature.networks.presentation.generated.resources.networks_fix_step_connect
import howyoudoin.feature.networks.presentation.generated.resources.networks_fix_now_connect
import howyoudoin.feature.networks.presentation.generated.resources.networks_fix_done
import howyoudoin.feature.networks.presentation.generated.resources.networks_untested
import howyoudoin.feature.networks.presentation.generated.resources.networks_discord_body
import howyoudoin.feature.networks.presentation.generated.resources.networks_discord_title
import androidx.compose.runtime.saveable.rememberSaveable
import howyoudoin.feature.networks.presentation.generated.resources.networks_connect
import howyoudoin.feature.networks.presentation.generated.resources.networks_connected_device
import howyoudoin.feature.networks.presentation.generated.resources.networks_add
import howyoudoin.feature.networks.presentation.generated.resources.networks_disconnect_title
import howyoudoin.feature.networks.presentation.generated.resources.networks_disconnect_body
import howyoudoin.feature.networks.presentation.generated.resources.networks_on_device_name
import howyoudoin.feature.networks.presentation.generated.resources.networks_on_device_workspace
import howyoudoin.feature.networks.presentation.generated.resources.login_state_connected
import howyoudoin.feature.networks.presentation.generated.resources.login_state_connecting
import howyoudoin.feature.networks.presentation.generated.resources.login_state_error
import howyoudoin.feature.networks.presentation.generated.resources.status_checking
import howyoudoin.feature.networks.presentation.generated.resources.status_coming_soon
import howyoudoin.feature.networks.presentation.generated.resources.status_not_configured
import howyoudoin.feature.networks.presentation.generated.resources.status_unreachable
import howyoudoin.feature.networks.presentation.generated.resources.status_not_connected
import howyoudoin.feature.networks.presentation.generated.resources.status_accounts
import org.jetbrains.compose.resources.pluralStringResource
import howyoudoin.feature.networks.presentation.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import howyoudoin.feature.networks.presentation.generated.resources.networks_cancel
import howyoudoin.feature.networks.presentation.generated.resources.networks_connect_your_chat_accounts_they
import howyoudoin.feature.networks.presentation.generated.resources.networks_disconnect
import howyoudoin.feature.networks.presentation.generated.resources.networks_howyoudoin_is_unofficial_whatsapp_messenger
import howyoudoin.feature.networks.presentation.generated.resources.networks_i_understand_continue
import howyoudoin.feature.networks.presentation.generated.resources.networks_networks
import howyoudoin.feature.networks.presentation.generated.resources.networks_read_the_terms_of_use
import howyoudoin.feature.networks.presentation.generated.resources.networks_your_account_could_be_blocked
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_back
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.core.designsystem.components.NetworkBadge
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.presentation.util.ObserveAsEvents
import com.devvalor.howyoudoing.networks.domain.NetworkLogin
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun NetworksRoot(
    onConnectClick: (network: ChatNetwork, slot: Int) -> Unit,
    onTutorialClick: (ChatNetwork) -> Unit,
    onTermsClick: () -> Unit,
    onPremiumClick: () -> Unit,
    onBack: () -> Unit,
    viewModel: NetworksViewModel = koinViewModel(),
    premium: Premium = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val premiumStatus by premium.status.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Re-runs whenever we come back from the connect flow.
    LaunchedEffect(Unit) { viewModel.onAction(NetworksAction.OnRefresh) }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is NetworksEvent.Error -> snackbarHostState.showSnackbar(event.message.asStringAsync())
        }
    }

    // Another account of a network already connected skips the tutorial: the user has done it before
    fun connect(network: ChatNetwork, slot: Int = 0) = when {
        slot == 0 && state.shouldShowTutorial(network) -> onTutorialClick(network)
        else -> onConnectClick(network, slot)
    }

    // Asked once, before the first network is connected; kept while the user reads the terms
    var riskWarningFor by rememberSaveable { mutableStateOf<ChatNetwork?>(null) }
    riskWarningFor?.let { network ->
        RiskWarningDialog(
            network = network,
            onAccept = {
                riskWarningFor = null
                viewModel.onAction(NetworksAction.OnRiskAccepted)
                connect(network)
            },
            onReadTerms = onTermsClick,
            onDismiss = { riskWarningFor = null },
        )
    }

    // A 2nd or 3rd account of a network is part of Premium; the ones already added keep working without it
    var premiumNeeded by rememberSaveable { mutableStateOf(false) }
    if (premiumNeeded) {
        MoreAccountsPremiumDialog(
            onSeePremium = {
                premiumNeeded = false
                onPremiumClick()
            },
            onDismiss = { premiumNeeded = false },
        )
    }

    NetworksScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onAction = { action ->
            when (action) {
                is NetworksAction.OnConnectClick ->
                    if (action.slot > 0 && !premiumStatus.isPremium && !state.isLinked(action.network, action.slot)) {
                        premiumNeeded = true
                    } else if (state.shouldWarnAboutRisk(action.network) && action.slot == 0) {
                        riskWarningFor = action.network
                    } else {
                        connect(action.network, action.slot)
                    }

                NetworksAction.OnBackClick -> onBack()
                else -> viewModel.onAction(action)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworksScreen(
    state: NetworksState,
    onAction: (NetworksAction) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.networks_networks), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { onAction(NetworksAction.OnBackClick) }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(Res.string.tutorial_back)
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        state.naming?.let { account ->
            NameAccountDialog(
                account = account,
                current = state.labels[account],
                onSave = { onAction(NetworksAction.OnLabelSave(account, it)) },
                onDismiss = { onAction(NetworksAction.OnLabelDismiss) }
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(HowYouDoinSpacing.md),
            verticalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                Text(
                    stringResource(Res.string.networks_connect_your_chat_accounts_they),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = HowYouDoinSpacing.sm),
                )
            }
            state.troubleshoot?.let { network ->
                item(key = "troubleshoot") {
                    TroubleshootCard(network, state.onDevice[network] ?: OnDeviceMessengerState.NotLinked)
                }
            }
            // The network being fixed comes right under its instructions
            val items = state.troubleshoot?.let { fixing -> state.items.sortedByDescending { it.network == fixing } }
                ?: state.items
            items(items, key = { it.network.name }) { item ->
                NetworkCard(
                    item = item,
                    onAction = onAction,
                    state = state,
                )
            }
        }
    }
}

/**
 * Reconnecting from the inbox didn't help: logging in again usually does. The steps, then how far the user got
 * (disconnected: connect now; working again: done).
 */
@Composable
private fun TroubleshootCard(network: ChatNetwork, onDevice: OnDeviceMessengerState) {
    Card(
        // Calm again once it works
        colors = if (onDevice is OnDeviceMessengerState.Connected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.xs),
            modifier = Modifier.padding(HowYouDoinSpacing.md),
        ) {
            Text(
                stringResource(Res.string.networks_fix_title, network.displayName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            (onDevice as? OnDeviceMessengerState.Failed)?.message?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(
                    stringResource(Res.string.networks_fix_reason, reason),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(stringResource(Res.string.networks_fix_intro), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(Res.string.networks_fix_step_disconnect, network.displayName),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(Res.string.networks_fix_step_connect), style = MaterialTheme.typography.bodyMedium)
            when (onDevice) {
                OnDeviceMessengerState.NotLinked -> stringResource(Res.string.networks_fix_now_connect)
                is OnDeviceMessengerState.Connected -> stringResource(Res.string.networks_fix_done, network.displayName)
                else -> null
            }?.let { progress ->
                Text(progress, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NetworkCard(
    item: NetworkItemUi,
    onAction: (NetworksAction) -> Unit,
    state: NetworksState,
) {
    val network = item.network
    // Any of the network's accounts on this phone (the first one may have been disconnected before another)
    val onDevice = state.linkedAccounts(network).firstOrNull()?.let { state.accounts[it] }
        ?: state.onDevice[network] ?: OnDeviceMessengerState.NotLinked
    val isComingSoon = item.status == NetworkStatus.ComingSoon
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(HowYouDoinSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NetworkBadge(network = network, size = 40.dp, borderColor = Color.Transparent, enabled = !isComingSoon)
                Spacer(Modifier.width(HowYouDoinSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            network.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (network == ChatNetwork.MATRIX) {
                            Spacer(Modifier.width(HowYouDoinSpacing.sm))
                            CallsBadge()
                        }
                    }
                    // Connected on the phone, the server bridge is optional: its trouble isn't this network's
                    val noLogins = (item.status as? NetworkStatus.Ready)?.logins.isNullOrEmpty()
                    Text(
                        if (onDevice != OnDeviceMessengerState.NotLinked && noLogins) {
                            stringResource(Res.string.networks_connected_device)
                        } else {
                            item.status.summary()
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    UntestedNote(network)
                }
                NetworkCardAction(item, onDevice, onAction)
            }
            if (item.status is NetworkStatus.Ready && item.status.logins.isNotEmpty()) {
                Spacer(Modifier.height(HowYouDoinSpacing.sm))
                item.status.logins.forEach { login ->
                    LoginRow(
                        login = login,
                        onDisconnect = { onAction(NetworksAction.OnDisconnectClick(network, login.id)) })
                }
            }
            OnDeviceAccountRows(network, state, onAction)
        }
    }
}

/** The card's button: Connect (or Add another server login), a spinner while loading, or nothing. */
@Composable
private fun NetworkCardAction(
    item: NetworkItemUi,
    onDevice: OnDeviceMessengerState,
    onAction: (NetworksAction) -> Unit,
) {
    val network = item.network
    when (item.status) {
        NetworkStatus.Loading if onDevice != OnDeviceMessengerState.NotLinked -> Unit
        NetworkStatus.Loading -> ActionSlot {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }

        NetworkStatus.ComingSoon -> Unit
        // Already linked on this phone (one account per network there): the row below disconnects it,
        // and a second Connect would only start the same link again. A failed link keeps the button.
        else if onDevice is OnDeviceMessengerState.Connected ||
            onDevice == OnDeviceMessengerState.Connecting -> Unit
        else -> ActionSlot {
            FilledTonalButton(onClick = { onAction(NetworksAction.OnConnectClick(network)) }) {
                val hasLogins = item.status is NetworkStatus.Ready && item.status.logins.isNotEmpty()
                Text(stringResource(if (hasLogins) Res.string.networks_add else Res.string.networks_connect))
            }
        }
    }
}

@Composable
private fun LoginRow(login: NetworkLogin, onDisconnect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = if (login.state == null || login.state == "CONNECTED") {
                Color(0xFF2E9E5B)
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(HowYouDoinSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(login.name, style = MaterialTheme.typography.bodyMedium)
            login.state?.let {
                Text(
                    loginStateLabel(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ActionSlot {
            TextButton(onClick = onDisconnect) {
                Icon(Icons.Rounded.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(HowYouDoinSpacing.xs))
                Text(stringResource(Res.string.networks_disconnect))
            }
        }
    }
}

/**
 * The column at a card's right edge that holds Connect and every Disconnect, centered in a common width so the
 * buttons line up whatever their labels (a longer translation just widens its own slot).
 */
@Composable
private fun ActionSlot(content: @Composable () -> Unit) {
    Box(modifier = Modifier.widthIn(min = ACTION_SLOT_MIN_WIDTH), contentAlignment = Alignment.Center) {
        content()
    }
}

private val ACTION_SLOT_MIN_WIDTH = 128.dp

/** Bridge login states ("CONNECTED", "ERROR: …") in words; states we don't know are shown as they come. */
@Composable
private fun loginStateLabel(state: String): String = when {
    state == "CONNECTED" -> stringResource(Res.string.login_state_connected)
    state == "CONNECTING" -> stringResource(Res.string.login_state_connecting)
    state.startsWith("ERROR: ") -> stringResource(Res.string.login_state_error, state.removePrefix("ERROR: "))
    else -> state.lowercase().replaceFirstChar(Char::uppercase)
}

@Composable
private fun NetworkStatus.summary(): String = when (this) {
    NetworkStatus.Loading -> stringResource(Res.string.status_checking)
    NetworkStatus.ComingSoon -> stringResource(Res.string.status_coming_soon)
    NetworkStatus.NotConfigured -> stringResource(Res.string.status_not_configured)
    is NetworkStatus.Unavailable -> stringResource(Res.string.status_unreachable, error.asString())
    is NetworkStatus.Ready -> if (logins.isEmpty()) {
        stringResource(Res.string.status_not_connected)
    } else {
        pluralStringResource(Res.plurals.status_accounts, logins.size, logins.size)
    }
}

/** Says when the network hasn't been connected with a real account yet, so connecting it may not work. */
@Composable
private fun UntestedNote(network: ChatNetwork) {
    if (!network.isUntested) return
    Text(
        stringResource(Res.string.networks_untested),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

@Composable
private fun RiskWarningDialog(
    network: ChatNetwork,
    onAccept: () -> Unit,
    onReadTerms: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDiscord = network == ChatNetwork.DISCORD
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isDiscord) {
                        Res.string.networks_discord_title
                    } else {
                        Res.string.networks_your_account_could_be_blocked
                    }
                )
            )
        },
        text = {
            Column {
                Text(
                    stringResource(
                        if (isDiscord) {
                            Res.string.networks_discord_body
                        } else {
                            Res.string.networks_howyoudoin_is_unofficial_whatsapp_messenger
                        }
                    )
                )
                TextButton(onClick = onReadTerms, modifier = Modifier.padding(top = HowYouDoinSpacing.sm)) {
                    Text(stringResource(Res.string.networks_read_the_terms_of_use))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(Res.string.networks_i_understand_continue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.networks_cancel)) } },
    )
}

@Composable
private fun MoreAccountsPremiumDialog(onSeePremium: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.WorkspacePremium, contentDescription = null) },
        title = { Text(stringResource(Res.string.networks_premium_accounts_title)) },
        text = { Text(stringResource(Res.string.networks_premium_accounts_body, MAX_ACCOUNTS_PER_NETWORK)) },
        confirmButton = {
            TextButton(onClick = onSeePremium) { Text(stringResource(Res.string.networks_premium_see)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.networks_premium_not_now)) }
        },
    )
}
