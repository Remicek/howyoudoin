package com.devvalor.howyoudoing.networks.presentation.list

import androidx.compose.runtime.saveable.rememberSaveable
import howyoudoin.feature.networks.presentation.generated.resources.networks_connect
import howyoudoin.feature.networks.presentation.generated.resources.networks_connected_device
import howyoudoin.feature.networks.presentation.generated.resources.networks_add
import howyoudoin.feature.networks.presentation.generated.resources.networks_disconnect_title
import howyoudoin.feature.networks.presentation.generated.resources.networks_disconnect_body
import howyoudoin.feature.networks.presentation.generated.resources.networks_on_device_name
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
import howyoudoin.feature.networks.presentation.generated.resources.networks_disconnect_and_delete
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
    onConnectClick: (ChatNetwork) -> Unit,
    onTutorialClick: (ChatNetwork) -> Unit,
    onTermsClick: () -> Unit,
    onBack: () -> Unit,
    viewModel: NetworksViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Re-runs whenever we come back from the connect flow.
    LaunchedEffect(Unit) { viewModel.onAction(NetworksAction.OnRefresh) }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is NetworksEvent.Error -> snackbarHostState.showSnackbar(event.message.asStringAsync())
        }
    }

    fun connect(network: ChatNetwork) =
        if (state.shouldShowTutorial(network)) onTutorialClick(network) else onConnectClick(network)

    // Asked once, before the first network is connected; kept while the user reads the terms
    var riskWarningFor by rememberSaveable { mutableStateOf<ChatNetwork?>(null) }
    riskWarningFor?.let { network ->
        RiskWarningDialog(
            onAccept = {
                riskWarningFor = null
                viewModel.onAction(NetworksAction.OnRiskAccepted)
                connect(network)
            },
            onReadTerms = onTermsClick,
            onDismiss = { riskWarningFor = null },
        )
    }

    NetworksScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onAction = { action ->
            when (action) {
                is NetworksAction.OnConnectClick ->
                    if (state.shouldWarnAboutRisk(action.network)) riskWarningFor = action.network else connect(action.network)
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
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Res.string.tutorial_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
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
            items(state.items, key = { it.network.name }) { item ->
                NetworkCard(
                    item = item,
                    onAction = onAction,
                    onDevice = state.onDevice[item.network] ?: OnDeviceMessengerState.NotLinked,
                )
            }
        }
    }
}

@Composable
private fun NetworkCard(
    item: NetworkItemUi,
    onAction: (NetworksAction) -> Unit,
    onDevice: OnDeviceMessengerState = OnDeviceMessengerState.NotLinked,
) {
    val network = item.network
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
                    Text(network.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        // Connected on the phone, the server bridge is optional: its trouble isn't this network's
                        if (onDevice != OnDeviceMessengerState.NotLinked && (item.status as? NetworkStatus.Ready)?.logins.isNullOrEmpty()) {
                            stringResource(Res.string.networks_connected_device)
                        } else item.status.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (item.status) {
                    NetworkStatus.Loading if onDevice != OnDeviceMessengerState.NotLinked -> Unit
                    NetworkStatus.Loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    NetworkStatus.ComingSoon -> Unit
                    else -> FilledTonalButton(onClick = { onAction(NetworksAction.OnConnectClick(network)) }) {
                        Text(stringResource(if (item.status is NetworkStatus.Ready && item.status.logins.isNotEmpty()) Res.string.networks_add else Res.string.networks_connect))
                    }
                }
            }
            if (item.status is NetworkStatus.Ready && item.status.logins.isNotEmpty()) {
                Spacer(Modifier.height(HowYouDoinSpacing.sm))
                item.status.logins.forEach { login ->
                    LoginRow(login = login, onDisconnect = { onAction(NetworksAction.OnDisconnectClick(network, login.id)) })
                }
            }
            if (onDevice != OnDeviceMessengerState.NotLinked) {
                var confirmDisconnect by remember { mutableStateOf(false) }
                if (confirmDisconnect) {
                    AlertDialog(
                        onDismissRequest = { confirmDisconnect = false },
                        title = { Text(stringResource(Res.string.networks_disconnect_title, network.displayName)) },
                        text = {
                            Text(
                                stringResource(Res.string.networks_disconnect_body, network.displayName)
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDisconnect = false
                                onAction(NetworksAction.OnDisconnectOnDeviceClick(network))
                            }) { Text(stringResource(Res.string.networks_disconnect_and_delete), color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(Res.string.networks_cancel)) } },
                    )
                }
                Spacer(Modifier.height(HowYouDoinSpacing.sm))
                LoginRow(
                    login = NetworkLogin(
                        id = "on-device",
                        name = stringResource(Res.string.networks_on_device_name, (onDevice as? OnDeviceMessengerState.Connected)?.accountName ?: network.displayName),
                        state = when (onDevice) {
                            is OnDeviceMessengerState.Connected -> "CONNECTED"
                            OnDeviceMessengerState.Connecting -> "CONNECTING"
                            is OnDeviceMessengerState.Failed -> "ERROR: ${onDevice.message}"
                            OnDeviceMessengerState.NotLinked -> null
                        }
                    ),
                    onDisconnect = { confirmDisconnect = true },
                )
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
            tint = if (login.state == null || login.state == "CONNECTED") Color(0xFF2E9E5B) else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(HowYouDoinSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(login.name, style = MaterialTheme.typography.bodyMedium)
            login.state?.let {
                Text(loginStateLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onDisconnect) {
            Icon(Icons.Rounded.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(HowYouDoinSpacing.xs))
            Text(stringResource(Res.string.networks_disconnect))
        }
    }
}

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

@Composable
private fun RiskWarningDialog(onAccept: () -> Unit, onReadTerms: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.networks_your_account_could_be_blocked)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.networks_howyoudoin_is_unofficial_whatsapp_messenger)
                )
                TextButton(onClick = onReadTerms, modifier = Modifier.padding(top = HowYouDoinSpacing.sm)) {
                    Text(stringResource(Res.string.networks_read_the_terms_of_use))
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(Res.string.networks_i_understand_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.networks_cancel)) } },
    )
}
