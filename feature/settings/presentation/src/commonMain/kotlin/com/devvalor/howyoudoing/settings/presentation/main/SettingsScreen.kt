package com.devvalor.howyoudoing.settings.presentation.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.settings.domain.AppLockRepository
import com.devvalor.howyoudoing.settings.domain.CrashReports
import com.devvalor.howyoudoing.settings.presentation.components.SettingsRow
import com.devvalor.howyoudoing.settings.presentation.components.SettingsScaffold
import com.devvalor.howyoudoing.settings.presentation.components.SettingsSectionHeader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.viewmodel.koinViewModel

data class SettingsState(
    val isLockEnabled: Boolean = false,
    val hasCrashReport: Boolean = false,
)

class SettingsViewModel(appLock: AppLockRepository, crashReports: CrashReports) : ViewModel() {
    val state = combine(appLock.observeSettings(), crashReports.observeLastReport()) { lock, crash ->
        SettingsState(isLockEnabled = lock.isEnabled, hasCrashReport = crash != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SettingsState())
}

@Composable
fun SettingsRoot(
    onNetworksClick: () -> Unit,
    onAppLockClick: () -> Unit,
    onCrashReportsClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onAboutClick: () -> Unit,
    onGuideClick: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(title = "Settings", onBack = onBack) {
        item {
            SettingsRow(
                title = "Networks",
                subtitle = "Connect or disconnect your accounts",
                icon = Icons.Rounded.Hub,
                onClick = onNetworksClick,
            )
        }
        item {
            SettingsRow(
                title = "How connecting works",
                subtitle = "Which login to use for each network",
                icon = Icons.AutoMirrored.Rounded.HelpOutline,
                onClick = onGuideClick,
            )
        }
        item { SettingsSectionHeader("Privacy") }
        item {
            SettingsRow(
                title = "App lock",
                subtitle = if (state.isLockEnabled) "On: PIN or fingerprint before showing your chats" else "Off",
                icon = Icons.Rounded.Lock,
                onClick = onAppLockClick,
            )
        }
        item {
            SettingsRow(
                title = "Crash reports",
                subtitle = if (state.hasCrashReport) "A report from the last crash is on this phone" else "Kept on this phone; you choose what to share",
                icon = Icons.Rounded.BugReport,
                onClick = onCrashReportsClick,
            )
        }
        item { SettingsSectionHeader("About") }
        item {
            SettingsRow(title = "Open-source licenses", icon = Icons.Rounded.Gavel, onClick = onLicensesClick)
        }
        item {
            SettingsRow(title = "About HowYouDoin", subtitle = "Version, source code, privacy", icon = Icons.Rounded.Info, onClick = onAboutClick)
        }
    }
}
