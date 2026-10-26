package com.devvalor.howyoudoing.settings.presentation.lock

import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.common_cancel
import howyoudoin.feature.settings.presentation.generated.resources.common_close
import howyoudoin.feature.settings.presentation.generated.resources.lock_15_min
import howyoudoin.feature.settings.presentation.generated.resources.lock_1_min
import howyoudoin.feature.settings.presentation.generated.resources.lock_5_min
import howyoudoin.feature.settings.presentation.generated.resources.lock_after
import howyoudoin.feature.settings.presentation.generated.resources.lock_change_pin
import howyoudoin.feature.settings.presentation.generated.resources.lock_choose
import howyoudoin.feature.settings.presentation.generated.resources.lock_confirm
import howyoudoin.feature.settings.presentation.generated.resources.lock_digits_hint
import howyoudoin.feature.settings.presentation.generated.resources.lock_enter_current
import howyoudoin.feature.settings.presentation.generated.resources.lock_fingerprint
import howyoudoin.feature.settings.presentation.generated.resources.lock_fingerprint_missing
import howyoudoin.feature.settings.presentation.generated.resources.lock_fingerprint_on
import howyoudoin.feature.settings.presentation.generated.resources.lock_forgot
import howyoudoin.feature.settings.presentation.generated.resources.lock_hide_text
import howyoudoin.feature.settings.presentation.generated.resources.lock_hide_text_sub
import howyoudoin.feature.settings.presentation.generated.resources.lock_immediately
import howyoudoin.feature.settings.presentation.generated.resources.lock_intro
import howyoudoin.feature.settings.presentation.generated.resources.lock_pin
import howyoudoin.feature.settings.presentation.generated.resources.lock_turn_off
import howyoudoin.feature.settings.presentation.generated.resources.lock_turn_on
import howyoudoin.feature.settings.presentation.generated.resources.lock_turn_on_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_app_lock
import org.jetbrains.compose.resources.stringResource
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
import androidx.compose.runtime.LaunchedEffect
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
    startSetup: Boolean = false,
    viewModel: AppLockSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (startSetup) viewModel.onAction(AppLockSettingsAction.OnStartSetup) }
    LaunchedEffect(state.isSetupFinished) { if (state.isSetupFinished) onBack() }
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
    SettingsScaffold(title = stringResource(Res.string.settings_app_lock), onBack = { onAction(AppLockSettingsAction.OnBackClick) }) {
        item {
            Text(
                stringResource(Res.string.lock_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
            )
        }
        if (!settings.isEnabled) {
            item {
                SettingsRow(
                    title = stringResource(Res.string.lock_turn_on),
                    subtitle = stringResource(Res.string.lock_turn_on_sub),
                    icon = Icons.Rounded.Lock,
                    onClick = { onAction(AppLockSettingsAction.OnTurnOnClick) },
                )
            }
        } else {
            item {
                SettingsSwitchRow(
                    title = stringResource(Res.string.lock_fingerprint),
                    subtitle = if (state.isBiometricAvailable) stringResource(Res.string.lock_fingerprint_on) else stringResource(Res.string.lock_fingerprint_missing),
                    icon = Icons.Rounded.Fingerprint,
                    checked = settings.isBiometricEnabled && state.isBiometricAvailable,
                    enabled = state.isBiometricAvailable,
                    onCheckedChange = { onAction(AppLockSettingsAction.OnBiometricChange(it)) },
                )
            }
            item {
                SettingsRow(
                    title = stringResource(Res.string.lock_after),
                    subtitle = settings.lockAfter.label(),
                    icon = Icons.Rounded.Timer,
                    onClick = { onAction(AppLockSettingsAction.OnLockAfterClick) },
                )
            }
            item {
                SettingsSwitchRow(
                    title = stringResource(Res.string.lock_hide_text),
                    subtitle = stringResource(Res.string.lock_hide_text_sub),
                    icon = Icons.Rounded.NotificationsOff,
                    checked = settings.hidesNotificationContent,
                    onCheckedChange = { onAction(AppLockSettingsAction.OnHideNotificationsChange(it)) },
                )
            }
            item { SettingsSectionHeader(stringResource(Res.string.lock_pin)) }
            item {
                SettingsRow(
                    title = stringResource(Res.string.lock_change_pin),
                    icon = Icons.Rounded.Password,
                    onClick = { onAction(AppLockSettingsAction.OnChangePinClick) },
                )
            }
            item {
                SettingsRow(
                    title = stringResource(Res.string.lock_turn_off),
                    icon = Icons.Rounded.LockOpen,
                    onClick = { onAction(AppLockSettingsAction.OnTurnOffClick) },
                )
            }
            item {
                Text(
                    stringResource(Res.string.lock_forgot),
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
            title = { Text(stringResource(Res.string.lock_after)) },
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
            confirmButton = { TextButton(onClick = { onAction(AppLockSettingsAction.OnDismissDialog) }) { Text(stringResource(Res.string.common_close)) } },
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
                                is PinStep.Current -> stringResource(Res.string.lock_enter_current)
                                PinStep.New -> stringResource(Res.string.lock_choose)
                                is PinStep.Confirm -> stringResource(Res.string.lock_confirm)
                            },
                            pinLength = state.pinLength,
                            message = state.pinMessage?.asString() ?: if (step == PinStep.New) stringResource(Res.string.lock_digits_hint) else null,
                            isError = state.pinError,
                            onDigit = { onAction(AppLockSettingsAction.OnDigit(it)) },
                            onDelete = { onAction(AppLockSettingsAction.OnDelete) },
                            onSubmit = { onAction(AppLockSettingsAction.OnSubmit) },
                        )
                        TextButton(onClick = { onAction(AppLockSettingsAction.OnCancelPin) }) { Text(stringResource(Res.string.common_cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
fun LockTimeout.label(): String = when (this) {
    LockTimeout.IMMEDIATELY -> stringResource(Res.string.lock_immediately)
    LockTimeout.ONE_MINUTE -> stringResource(Res.string.lock_1_min)
    LockTimeout.FIVE_MINUTES -> stringResource(Res.string.lock_5_min)
    LockTimeout.FIFTEEN_MINUTES -> stringResource(Res.string.lock_15_min)
}
