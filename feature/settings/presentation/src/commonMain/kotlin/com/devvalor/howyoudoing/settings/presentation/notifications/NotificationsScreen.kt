package com.devvalor.howyoudoing.settings.presentation.notifications

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.domain.app.NotificationChoices
import com.devvalor.howyoudoing.core.domain.app.NotificationKind
import com.devvalor.howyoudoing.core.domain.app.NotificationSettings
import com.devvalor.howyoudoing.core.domain.app.SystemNotificationSettings
import com.devvalor.howyoudoing.settings.presentation.components.SettingsRow
import com.devvalor.howyoudoing.settings.presentation.components.SettingsScaffold
import com.devvalor.howyoudoing.settings.presentation.components.SettingsSectionHeader
import com.devvalor.howyoudoing.settings.presentation.components.SettingsSwitchRow
import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.notif_calls
import howyoudoin.feature.settings.presentation.generated.resources.notif_calls_header
import howyoudoin.feature.settings.presentation.generated.resources.notif_calls_sub
import howyoudoin.feature.settings.presentation.generated.resources.notif_messages
import howyoudoin.feature.settings.presentation.generated.resources.notif_messages_header
import howyoudoin.feature.settings.presentation.generated.resources.notif_messages_sub
import howyoudoin.feature.settings.presentation.generated.resources.notif_mute_chat_note
import howyoudoin.feature.settings.presentation.generated.resources.notif_sound
import howyoudoin.feature.settings.presentation.generated.resources.notif_sound_sub
import howyoudoin.feature.settings.presentation.generated.resources.notif_system_off
import howyoudoin.feature.settings.presentation.generated.resources.notif_system_off_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

class NotificationsViewModel(
    private val settings: NotificationSettings,
    private val system: SystemNotificationSettings,
) : ViewModel() {

    val choices = settings.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    private val _isSystemOn = MutableStateFlow(system.areEnabled())

    /** False when the phone blocks all of the app's notifications; then nothing here has any effect. */
    val isSystemOn = _isSystemOn.asStateFlow()

    val canOpenSystemSettings = system.isSupported

    /** The user may have changed it in Android's settings and come back. */
    fun refresh() {
        _isSystemOn.value = system.areEnabled()
    }

    fun setMessages(enabled: Boolean) {
        viewModelScope.launch { settings.setMessages(enabled) }
    }

    fun setCalls(enabled: Boolean) {
        viewModelScope.launch { settings.setCalls(enabled) }
    }

    fun openSystem() = system.openApp()

    fun openSound(kind: NotificationKind) = system.open(kind)
}

@Composable
fun NotificationsRoot(onBack: () -> Unit, viewModel: NotificationsViewModel = koinViewModel()) {
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    val isSystemOn by viewModel.isSystemOn.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    NotificationsScreen(
        choices = choices ?: NotificationChoices(),
        isSystemOn = isSystemOn,
        canOpenSystemSettings = viewModel.canOpenSystemSettings,
        onMessagesChange = viewModel::setMessages,
        onCallsChange = viewModel::setCalls,
        onOpenSystem = viewModel::openSystem,
        onOpenSound = viewModel::openSound,
        onBack = onBack,
    )
}

@Composable
fun NotificationsScreen(
    choices: NotificationChoices,
    isSystemOn: Boolean,
    canOpenSystemSettings: Boolean,
    onMessagesChange: (Boolean) -> Unit,
    onCallsChange: (Boolean) -> Unit,
    onOpenSystem: () -> Unit,
    onOpenSound: (NotificationKind) -> Unit,
    onBack: () -> Unit,
) {
    SettingsScaffold(title = stringResource(Res.string.settings_notifications), onBack = onBack) {
        if (!isSystemOn) {
            item {
                SettingsRow(
                    title = stringResource(Res.string.notif_system_off),
                    subtitle = stringResource(Res.string.notif_system_off_sub),
                    icon = Icons.Rounded.NotificationsOff,
                    onClick = onOpenSystem.takeIf { canOpenSystemSettings },
                )
            }
        }
        item { SettingsSectionHeader(stringResource(Res.string.notif_messages_header)) }
        item {
            SettingsSwitchRow(
                title = stringResource(Res.string.notif_messages),
                subtitle = stringResource(Res.string.notif_messages_sub),
                icon = Icons.AutoMirrored.Rounded.Chat,
                checked = choices.messages,
                onCheckedChange = onMessagesChange,
            )
        }
        if (canOpenSystemSettings) {
            item {
                SettingsRow(
                    title = stringResource(Res.string.notif_sound),
                    subtitle = stringResource(Res.string.notif_sound_sub),
                    icon = Icons.Rounded.VolumeUp,
                    onClick = { onOpenSound(NotificationKind.MESSAGES) },
                )
            }
        }
        item { SettingsRow(title = stringResource(Res.string.notif_mute_chat_note)) }
        item { SettingsSectionHeader(stringResource(Res.string.notif_calls_header)) }
        item {
            SettingsSwitchRow(
                title = stringResource(Res.string.notif_calls),
                subtitle = stringResource(Res.string.notif_calls_sub),
                icon = Icons.Rounded.Call,
                checked = choices.calls,
                onCheckedChange = onCallsChange,
            )
        }
        if (canOpenSystemSettings) {
            item {
                SettingsRow(
                    title = stringResource(Res.string.notif_sound),
                    subtitle = stringResource(Res.string.notif_sound_sub),
                    icon = Icons.Rounded.VolumeUp,
                    onClick = { onOpenSound(NotificationKind.CALLS) },
                )
            }
        }
    }
}
