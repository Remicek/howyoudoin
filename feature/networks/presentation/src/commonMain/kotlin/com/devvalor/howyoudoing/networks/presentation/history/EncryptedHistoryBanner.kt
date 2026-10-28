package com.devvalor.howyoudoing.networks.presentation.history

import howyoudoin.feature.networks.presentation.generated.resources.history_restoring_device
import howyoudoin.feature.networks.presentation.generated.resources.history_restoring
import howyoudoin.feature.networks.presentation.generated.resources.history_ok
import howyoudoin.feature.networks.presentation.generated.resources.history_restore_device
import howyoudoin.feature.networks.presentation.generated.resources.history_restore
import howyoudoin.feature.networks.presentation.generated.resources.history_enter_pin
import howyoudoin.feature.networks.presentation.generated.resources.history_not_now
import howyoudoin.feature.networks.presentation.generated.resources.history_pin_warning
import howyoudoin.feature.networks.presentation.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import howyoudoin.feature.networks.presentation.generated.resources.encryptedhistory_enter_your_messenger_pin
import howyoudoin.feature.networks.presentation.generated.resources.encryptedhistory_messenger_pin
import howyoudoin.feature.networks.presentation.generated.resources.encryptedhistory_restore
import howyoudoin.feature.networks.presentation.generated.resources.encryptedhistory_restoring_encrypted_history
import howyoudoin.feature.networks.presentation.generated.resources.encryptedhistory_the_6_digit_pin_you
import howyoudoin.feature.networks.presentation.generated.resources.encryptedhistory_your_end_to_end_encrypted
import howyoudoin.feature.networks.presentation.generated.resources.networks_cancel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import org.koin.compose.viewmodel.koinViewModel

/**
 * Shown above the inbox when Messenger's end-to-end encrypted chats are missing their history and the user can
 * bring it back with their Messenger PIN.
 */
@Composable
fun EncryptedHistoryBannerRoot(
    encryptedNetworks: Set<ChatNetwork>,
    viewModel: EncryptedHistoryViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(encryptedNetworks) {
        viewModel.onAction(EncryptedHistoryAction.OnEncryptedNetworksChange(encryptedNetworks))
    }
    EncryptedHistoryBanner(state = state, onAction = viewModel::onAction)
}

@Composable
fun EncryptedHistoryBanner(
    state: EncryptedHistoryState,
    onAction: (EncryptedHistoryAction) -> Unit,
) {
    when {
        state.isRestoring -> BannerCard(
            title = stringResource(Res.string.encryptedhistory_restoring_encrypted_history),
            body = if (state.isOnDevice) {
                stringResource(Res.string.history_restoring_device)
            } else {
                stringResource(Res.string.history_restoring)
            },
            primary = stringResource(Res.string.history_ok) to { onAction(EncryptedHistoryAction.OnDismissRestoring) },
        )

        state.isOffered -> BannerCard(
            title = stringResource(
                if (state.isOnDevice) Res.string.history_restore_device else Res.string.history_restore
            ),
            body = stringResource(Res.string.encryptedhistory_your_end_to_end_encrypted),
            primary = stringResource(Res.string.history_enter_pin) to {
                onAction(EncryptedHistoryAction.OnEnterPinClick)
            },
            secondary = stringResource(Res.string.history_not_now) to {
                onAction(EncryptedHistoryAction.OnNotNowClick)
            },
        )
    }
    if (state.isDialogOpen) PinDialog(state, onAction)
}

@Composable
private fun BannerCard(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
    ) {
        Column(Modifier.padding(HowYouDoinSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(HowYouDoinSpacing.sm))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.padding(top = HowYouDoinSpacing.xs))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                secondary?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
                TextButton(onClick = primary.second) { Text(primary.first, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun PinDialog(state: EncryptedHistoryState, onAction: (EncryptedHistoryAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(EncryptedHistoryAction.OnDismissDialog) },
        title = { Text(stringResource(Res.string.encryptedhistory_enter_your_messenger_pin)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.encryptedhistory_the_6_digit_pin_you),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.padding(top = HowYouDoinSpacing.sm))
                OutlinedTextField(
                    value = state.pin,
                    onValueChange = { onAction(EncryptedHistoryAction.OnPinChange(it)) },
                    label = { Text(stringResource(Res.string.encryptedhistory_messenger_pin)) },
                    singleLine = true,
                    enabled = !state.isSubmitting,
                    isError = state.error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = HowYouDoinSpacing.sm))
                Text(
                    state.error?.asString() ?: stringResource(Res.string.history_pin_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAction(EncryptedHistoryAction.OnRestoreClick) }, enabled = state.canSubmit) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(Res.string.encryptedhistory_restore))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(EncryptedHistoryAction.OnDismissDialog) }, enabled = !state.isSubmitting) {
                Text(stringResource(Res.string.networks_cancel))
            }
        },
    )
}
