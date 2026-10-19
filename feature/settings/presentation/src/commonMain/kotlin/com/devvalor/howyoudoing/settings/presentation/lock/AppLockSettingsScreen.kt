package com.devvalor.howyoudoing.settings.presentation.lock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.settings.domain.LockTimeout
import com.devvalor.howyoudoing.settings.presentation.components.SettingsRow
import com.devvalor.howyoudoing.settings.presentation.components.SettingsScaffold
import com.devvalor.howyoudoing.settings.presentation.components.SettingsSectionHeader
import com.devvalor.howyoudoing.settings.presentation.components.SettingsSwitchRow
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AppLockSettingsRoot(
    onBack: () -> Unit,
    viewModel: AppLockSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppLockSettingsScreen(
        state = state,
        onAction = { action ->
            when (action) {
                AppLockSettingsAction.OnBackClick -> onBack()
                else -> viewModel.onAction(action)
            }
        },
    )
}

@Composable
fun AppLockSettingsScreen(
    state: AppLockSettingsState,
    onAction: (AppLockSettingsAction) -> Unit,
) {
    val settings = state.settings
    SettingsScaffold(title = "App lock", onBack = { onAction(AppLockSettingsAction.OnBackClick) }) {
        item {
            Text(
                "Ask for a PIN or your fingerprint before showing your chats, when the app opens and when you come back to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
            )
        }
        if (!settings.isEnabled) {
            item {
                SettingsRow(
                    title = "Turn on app lock",
                    subtitle = "Choose a PIN of 4 to 8 digits",
                    icon = Icons.Rounded.Lock,
                    onClick = { onAction(AppLockSettingsAction.OnTurnOnClick) },
                )
            }
        } else {
            item {
                SettingsSwitchRow(
                    title = "Unlock with fingerprint",
                    subtitle = if (state.isBiometricAvailable) "Or face, where the phone supports it" else "Set up a fingerprint in the phone's settings first",
                    icon = Icons.Rounded.Fingerprint,
                    checked = settings.isBiometricEnabled && state.isBiometricAvailable,
                    enabled = state.isBiometricAvailable,
                    onCheckedChange = { onAction(AppLockSettingsAction.OnBiometricChange(it)) },
                )
            }
            item {
                SettingsRow(
                    title = "Lock when away for",
                    subtitle = settings.lockAfter.label(),
                    icon = Icons.Rounded.Timer,
                    onClick = { onAction(AppLockSettingsAction.OnLockAfterClick) },
                )
            }
            item {
                SettingsSwitchRow(
                    title = "Hide message text in notifications",
                    subtitle = "Notifications only say there's a new message",
                    icon = Icons.Rounded.NotificationsOff,
                    checked = settings.hidesNotificationContent,
                    onCheckedChange = { onAction(AppLockSettingsAction.OnHideNotificationsChange(it)) },
                )
            }
            item { SettingsSectionHeader("PIN") }
            item {
                SettingsRow(
                    title = "Change PIN",
                    icon = Icons.Rounded.Password,
                    onClick = { onAction(AppLockSettingsAction.OnChangePinClick) },
                )
            }
            item {
                SettingsRow(
                    title = "Turn off app lock",
                    icon = Icons.Rounded.LockOpen,
                    onClick = { onAction(AppLockSettingsAction.OnTurnOffClick) },
                )
            }
            item {
                Text(
                    "Forgot the PIN? Clear the app's data in the phone's settings; you'll then connect your accounts again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
                )
            }
        }
    }

    if (state.isTimeoutDialogOpen) {
        AlertDialog(
            onDismissRequest = { onAction(AppLockSettingsAction.OnDismissDialog) },
            title = { Text("Lock when away for") },
            text = {
                Column {
                    LockTimeout.entries.forEach { timeout ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = timeout == settings.lockAfter,
                                    role = Role.RadioButton,
                                    onClick = { onAction(AppLockSettingsAction.OnLockAfterSelect(timeout)) },
                                )
                                .padding(vertical = HowYouDoinSpacing.xs),
                        ) {
                            RadioButton(selected = timeout == settings.lockAfter, onClick = null)
                            Text(timeout.label(), modifier = Modifier.padding(start = HowYouDoinSpacing.sm))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onAction(AppLockSettingsAction.OnDismissDialog) }) { Text("Close") } },
        )
    }

    state.pinStep?.let { step ->
        Dialog(
            onDismissRequest = { onAction(AppLockSettingsAction.OnCancelPin) },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PinPad(
                            title = when (step) {
                                is PinStep.Current -> "Enter your current PIN"
                                PinStep.New -> "Choose a PIN"
                                is PinStep.Confirm -> "Confirm your PIN"
                            },
                            pinLength = state.pinLength,
                            message = state.pinMessage ?: if (step == PinStep.New) "4 to 8 digits" else null,
                            isError = state.pinError,
                            onDigit = { onAction(AppLockSettingsAction.OnDigit(it)) },
                            onDelete = { onAction(AppLockSettingsAction.OnDelete) },
                            onSubmit = { onAction(AppLockSettingsAction.OnSubmit) },
                        )
                        TextButton(onClick = { onAction(AppLockSettingsAction.OnCancelPin) }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

fun LockTimeout.label(): String = when (this) {
    LockTimeout.IMMEDIATELY -> "Immediately"
    LockTimeout.ONE_MINUTE -> "1 minute"
    LockTimeout.FIVE_MINUTES -> "5 minutes"
    LockTimeout.FIFTEEN_MINUTES -> "15 minutes"
}
