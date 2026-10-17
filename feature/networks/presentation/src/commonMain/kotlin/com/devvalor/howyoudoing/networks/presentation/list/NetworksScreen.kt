package com.devvalor.howyoudoing.networks.presentation.list

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

    NetworksScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onAction = { action ->
            when (action) {
                is NetworksAction.OnConnectClick ->
                    if (state.shouldShowTutorial(action.network)) onTutorialClick(action.network) else onConnectClick(action.network)
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
                title = { Text("Networks", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { onAction(NetworksAction.OnBackClick) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
                    "Connect your chat accounts. Each one is bridged into your inbox through your Matrix server.",
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
                        item.status.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (item.status) {
                    NetworkStatus.Loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    NetworkStatus.ComingSoon -> Unit
                    else -> FilledTonalButton(onClick = { onAction(NetworksAction.OnConnectClick(network)) }) {
                        Text(if (item.status is NetworkStatus.Ready && item.status.logins.isNotEmpty()) "Add" else "Connect")
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
                Spacer(Modifier.height(HowYouDoinSpacing.sm))
                LoginRow(
                    login = NetworkLogin(
                        id = "on-device",
                        name = ((onDevice as? OnDeviceMessengerState.Connected)?.accountName ?: network.displayName) + " · on this device",
                        state = when (onDevice) {
                            is OnDeviceMessengerState.Connected -> "CONNECTED"
                            OnDeviceMessengerState.Connecting -> "CONNECTING"
                            is OnDeviceMessengerState.Failed -> "ERROR: ${onDevice.message}"
                            OnDeviceMessengerState.NotLinked -> null
                        }
                    ),
                    onDisconnect = { onAction(NetworksAction.OnDisconnectOnDeviceClick(network)) },
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
                Text(it.lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onDisconnect) {
            Icon(Icons.Rounded.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(HowYouDoinSpacing.xs))
            Text("Disconnect")
        }
    }
}

@Composable
private fun NetworkStatus.summary(): String = when (this) {
    NetworkStatus.Loading -> "Checking bridge…"
    NetworkStatus.ComingSoon -> "Coming soon"
    NetworkStatus.NotConfigured -> "Bridge not set up yet"
    is NetworkStatus.Unavailable -> "Bridge unreachable: ${error.asString()}"
    is NetworkStatus.Ready -> when (logins.size) {
        0 -> "Not connected"
        1 -> "1 account connected"
        else -> "${logins.size} accounts connected"
    }
}
