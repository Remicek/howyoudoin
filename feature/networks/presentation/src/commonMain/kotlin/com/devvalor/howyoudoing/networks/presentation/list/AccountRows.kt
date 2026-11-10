package com.devvalor.howyoudoing.networks.presentation.list

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.devvalor.howyoudoing.core.designsystem.components.AccountColours
import com.devvalor.howyoudoing.core.designsystem.components.AccountDot
import com.devvalor.howyoudoing.core.designsystem.components.AccountTag
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.AccountLabel
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.core.domain.ondevice.MAX_ACCOUNTS_PER_NETWORK
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import howyoudoin.feature.networks.presentation.generated.resources.Res
import howyoudoin.feature.networks.presentation.generated.resources.login_state_connected
import howyoudoin.feature.networks.presentation.generated.resources.login_state_connecting
import howyoudoin.feature.networks.presentation.generated.resources.login_state_error
import howyoudoin.feature.networks.presentation.generated.resources.networks_account_colour
import howyoudoin.feature.networks.presentation.generated.resources.networks_account_default
import howyoudoin.feature.networks.presentation.generated.resources.networks_account_more
import howyoudoin.feature.networks.presentation.generated.resources.networks_account_name_hint
import howyoudoin.feature.networks.presentation.generated.resources.networks_account_reconnect
import howyoudoin.feature.networks.presentation.generated.resources.networks_account_rename
import howyoudoin.feature.networks.presentation.generated.resources.networks_accounts_full
import howyoudoin.feature.networks.presentation.generated.resources.networks_add_account
import howyoudoin.feature.networks.presentation.generated.resources.networks_cancel
import howyoudoin.feature.networks.presentation.generated.resources.networks_disconnect
import howyoudoin.feature.networks.presentation.generated.resources.networks_disconnect_body
import howyoudoin.feature.networks.presentation.generated.resources.networks_disconnect_title
import howyoudoin.feature.networks.presentation.generated.resources.networks_name_account_body
import howyoudoin.feature.networks.presentation.generated.resources.networks_name_account_title
import howyoudoin.feature.networks.presentation.generated.resources.networks_on_device_name
import howyoudoin.feature.networks.presentation.generated.resources.networks_on_device_workspace
import howyoudoin.feature.networks.presentation.generated.resources.networks_save
import org.jetbrains.compose.resources.stringResource

/** What an account is called on screen: the user's name for it, else "Account 2". */
@Composable
fun AccountLabel?.nameOr(account: AccountRef): String =
    this?.name?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.networks_account_default, account.slot + 1)

/** The colour shown for an account without one chosen: one per slot. */
fun AccountLabel?.colourOr(account: AccountRef): Int = this?.colour ?: account.slot

/**
 * A network's accounts on this phone: one row each (named and coloured once there's more than one), then "Add another
 * account · 2 of 3" or, with all taken, how to make room.
 */
@Composable
internal fun OnDeviceAccountRows(
    network: ChatNetwork,
    state: NetworksState,
    onAction: (NetworksAction) -> Unit
) {
    val linked = state.linkedAccounts(network)
    if (linked.isEmpty()) return
    val showLabels = linked.size > 1
    Spacer(Modifier.height(HowYouDoinSpacing.sm))
    linked.forEach { account ->
        AccountRow(
            account = account,
            accountState = state.accounts[account] ?: OnDeviceMessengerState.NotLinked,
            label = if (showLabels) {
                AccountLabel(state.labels[account].nameOr(account), state.labels[account].colourOr(account))
            } else null,
            hasMenu = state.isMultiAccount(network),
            onAction = onAction
        )
    }
    if (state.isMultiAccount(network)) {
        val freeSlot = state.freeSlot(network)
        if (freeSlot != null) {
            TextButton(onClick = { onAction(NetworksAction.OnConnectClick(network, freeSlot)) }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(HowYouDoinSpacing.xs))
                Text(stringResource(Res.string.networks_add_account, linked.size + 1, MAX_ACCOUNTS_PER_NETWORK))
            }
        } else {
            Text(
                stringResource(Res.string.networks_accounts_full, MAX_ACCOUNTS_PER_NETWORK),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = HowYouDoinSpacing.xs)
            )
        }
    }
}

@Composable
private fun AccountRow(
    account: AccountRef,
    accountState: OnDeviceMessengerState,
    label: AccountLabel?,
    hasMenu: Boolean,
    onAction: (NetworksAction) -> Unit
) {
    val network = account.network
    var confirmDisconnect by remember { mutableStateOf(false) }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = {
                val name = label?.let { "${network.displayName} · ${it.name}" } ?: network.displayName
                Text(stringResource(Res.string.networks_disconnect_title, name))
            },
            text = { Text(stringResource(Res.string.networks_disconnect_body, network.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    onAction(NetworksAction.OnDisconnectOnDeviceClick(network, account.slot))
                }) {
                    Text(stringResource(Res.string.networks_disconnect), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(Res.string.networks_cancel)) }
            },
        )
    }
    val connected = accountState as? OnDeviceMessengerState.Connected
    val accountName = connected?.accountName ?: network.displayName
    val workspace = connected?.workspace
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = if (connected != null) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(HowYouDoinSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            label?.let {
                val rename = Modifier.clickable { onAction(NetworksAction.OnEditLabelClick(account)) }
                AccountTag(it.name, it.colour, rename)
            }
            Text(
                if (workspace != null) {
                    stringResource(Res.string.networks_on_device_workspace, accountName, workspace)
                } else {
                    stringResource(Res.string.networks_on_device_name, accountName)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                when (accountState) {
                    is OnDeviceMessengerState.Connected -> stringResource(Res.string.login_state_connected)
                    is OnDeviceMessengerState.Failed ->
                        stringResource(Res.string.login_state_error, accountState.message)
                    else -> stringResource(Res.string.login_state_connecting)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (hasMenu) {
            AccountMenu(
                description = stringResource(Res.string.networks_account_more, label?.name ?: accountName),
                onRename = { onAction(NetworksAction.OnEditLabelClick(account)) },
                onReconnect = { onAction(NetworksAction.OnReconnectAccountClick(account)) },
                onDisconnect = { confirmDisconnect = true }
            )
        } else {
            TextButton(onClick = { confirmDisconnect = true }) { Text(stringResource(Res.string.networks_disconnect)) }
        }
    }
}

@Composable
private fun AccountMenu(description: String, onRename: () -> Unit, onReconnect: () -> Unit, onDisconnect: () -> Unit) {
    var isOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { isOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = description) }
        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.networks_account_rename)) },
                onClick = { isOpen = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.networks_account_reconnect)) },
                onClick = { isOpen = false; onReconnect() }
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(Res.string.networks_disconnect), color = MaterialTheme.colorScheme.error)
                },
                onClick = { isOpen = false; onDisconnect() }
            )
        }
    }
}

/** Names an account and picks its colour; offered when a network gets its second account, and from its menu. */
@Composable
internal fun NameAccountDialog(
    account: AccountRef,
    current: AccountLabel?,
    onSave: (AccountLabel) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultName = current.nameOr(account)
    var name by remember(account) { mutableStateOf(current?.name.orEmpty()) }
    var colour by remember(account) { mutableIntStateOf(current.colourOr(account)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.networks_name_account_title, account.network.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm)) {
                Text(stringResource(Res.string.networks_name_account_body, account.network.displayName))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.networks_account_name_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                val colourLabel = stringResource(Res.string.networks_account_colour)
                Row(horizontalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm)) {
                    AccountColours.indices.forEach { index ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .border(
                                    2.dp,
                                    if (index == colour) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    CircleShape
                                )
                                .clickable { colour = index }
                                .semantics {
                                    contentDescription = "$colourLabel ${index + 1}"
                                    selected = index == colour
                                }
                        ) { AccountDot(index, size = 22.dp) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(AccountLabel(name.ifBlank { defaultName }, colour)) }) {
                Text(stringResource(Res.string.networks_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.networks_cancel)) } }
    )
}

private const val MAX_NAME_LENGTH = 24
