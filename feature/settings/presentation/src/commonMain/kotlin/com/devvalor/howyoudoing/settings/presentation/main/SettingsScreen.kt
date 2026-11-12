package com.devvalor.howyoudoing.settings.presentation.main

import howyoudoin.feature.settings.presentation.generated.resources.settings_premium_sub_active
import howyoudoin.feature.settings.presentation.generated.resources.settings_premium_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_premium
import androidx.compose.material.icons.rounded.WorkspacePremium
import com.devvalor.howyoudoing.core.domain.premium.Premium
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StarRate
import com.devvalor.howyoudoing.core.domain.app.AppPromotion
import com.devvalor.howyoudoing.core.domain.app.AppTour
import howyoudoin.feature.settings.presentation.generated.resources.settings_rate
import howyoudoin.feature.settings.presentation.generated.resources.settings_rate_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_share
import howyoudoin.feature.settings.presentation.generated.resources.settings_share_message
import howyoudoin.feature.settings.presentation.generated.resources.settings_share_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_tour
import howyoudoin.feature.settings.presentation.generated.resources.settings_tour_sub
import org.koin.compose.koinInject
import com.devvalor.howyoudoing.core.domain.ondevice.TelegramApiKeys
import howyoudoin.feature.settings.presentation.generated.resources.settings_telegram_keys
import howyoudoin.feature.settings.presentation.generated.resources.telegram_keys_using_builtin
import howyoudoin.feature.settings.presentation.generated.resources.telegram_keys_using_own
import howyoudoin.feature.settings.presentation.generated.resources.settings_log_out_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_log_out
import androidx.compose.material.icons.automirrored.rounded.Logout
import com.devvalor.howyoudoing.core.domain.auth.AuthService
import howyoudoin.feature.settings.presentation.generated.resources.settings_not_backed_up
import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.settings_about
import howyoudoin.feature.settings.presentation.generated.resources.settings_about_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_app_lock
import howyoudoin.feature.settings.presentation.generated.resources.settings_app_lock_on
import howyoudoin.feature.settings.presentation.generated.resources.settings_appearance
import howyoudoin.feature.settings.presentation.generated.resources.settings_notifications
import howyoudoin.feature.settings.presentation.generated.resources.settings_notifications_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_appearance_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_backup_logins
import howyoudoin.feature.settings.presentation.generated.resources.settings_backup_logins_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_crash_none
import howyoudoin.feature.settings.presentation.generated.resources.settings_ask_before_letting_in
import howyoudoin.feature.settings.presentation.generated.resources.settings_ask_before_letting_in_sub
import androidx.compose.material.icons.rounded.HowToReg
import com.devvalor.howyoudoing.core.domain.app.CallSettings
import howyoudoin.feature.settings.presentation.generated.resources.settings_crash_present
import howyoudoin.feature.settings.presentation.generated.resources.settings_crash_reports
import howyoudoin.feature.settings.presentation.generated.resources.settings_days_ago
import howyoudoin.feature.settings.presentation.generated.resources.settings_google_backup
import howyoudoin.feature.settings.presentation.generated.resources.settings_guide
import howyoudoin.feature.settings.presentation.generated.resources.settings_guide_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_last_backup
import howyoudoin.feature.settings.presentation.generated.resources.settings_licenses
import howyoudoin.feature.settings.presentation.generated.resources.settings_networks
import howyoudoin.feature.settings.presentation.generated.resources.settings_networks_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_off
import howyoudoin.feature.settings.presentation.generated.resources.settings_section_about
import howyoudoin.feature.settings.presentation.generated.resources.settings_section_privacy
import howyoudoin.feature.settings.presentation.generated.resources.settings_terms
import howyoudoin.feature.settings.presentation.generated.resources.settings_terms_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_title
import howyoudoin.feature.settings.presentation.generated.resources.settings_today
import howyoudoin.feature.settings.presentation.generated.resources.settings_yesterday
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Policy
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
import com.devvalor.howyoudoing.settings.presentation.components.SettingsSwitchRow
import com.devvalor.howyoudoing.core.domain.backup.LoginBackup
import androidx.compose.material.icons.rounded.CloudUpload
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.viewmodel.koinViewModel

data class SettingsState(
    val isLockEnabled: Boolean = false,
    val hasCrashReport: Boolean = false,
    val canBackUpLogins: Boolean = false,
    val isLoginBackupOn: Boolean = false,
    /** When the phone last backed the app up; null if it hasn't (Google backup may be off). */
    val lastBackupMillis: Long? = null,
    val canUseOwnTelegramKeys: Boolean = false,
    val usesOwnTelegramKeys: Boolean = false,
    /** The person who opens a call's link waits until they're let in. */
    val asksBeforeLettingIn: Boolean = false,
)

@Suppress("LongParameterList")
class SettingsViewModel(
    appLock: AppLockRepository,
    crashReports: CrashReports,
    private val loginBackup: LoginBackup,
    private val authService: AuthService,
    private val telegramKeys: TelegramApiKeys,
    private val appTour: AppTour,
    private val callSettings: CallSettings,
) : ViewModel() {
    val state = combine(
        appLock.observeSettings(),
        crashReports.observeLastReport(),
        loginBackup.observeEnabled(),
        flow { emit(loginBackup.lastBackupMillis()) },
        telegramKeys.observeOwn()
    ) { lock, crash, backup, lastBackup, ownTelegramKeys ->
        SettingsState(
            isLockEnabled = lock.isEnabled,
            hasCrashReport = crash != null,
            canBackUpLogins = loginBackup.isSupported,
            isLoginBackupOn = backup,
            lastBackupMillis = lastBackup,
            canUseOwnTelegramKeys = telegramKeys.isSupported,
            usesOwnTelegramKeys = ownTelegramKeys != null
        )
    }.combine(callSettings.observeAskBeforeLettingIn()) { state, ask ->
        state.copy(asksBeforeLettingIn = ask)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SettingsState())

    fun openBackupSettings() = loginBackup.openSystemBackupSettings()

    fun setAskBeforeLettingIn(ask: Boolean) {
        viewModelScope.launch { callSettings.setAskBeforeLettingIn(ask) }
    }

    fun setLoginBackup(on: Boolean) {
        viewModelScope.launch { loginBackup.setEnabled(on) }
    }

    /** The inbox shows its tour again (Settings then goes back to it). */
    fun showTourAgain() {
        viewModelScope.launch { appTour.showAgain() }
    }

    /** Ends the session; the app then goes back to the welcome screen. */
    fun logOut() {
        viewModelScope.launch { authService.logout() }
    }
}

@Composable
fun SettingsRoot(
    onNetworksClick: () -> Unit,
    onAppLockClick: () -> Unit,
    onCrashReportsClick: () -> Unit,
    onAppearanceClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onAboutClick: () -> Unit,
    onTermsClick: () -> Unit,
    onGuideClick: () -> Unit,
    onTelegramKeysClick: () -> Unit,
    onPremiumClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    promotion: AppPromotion = koinInject(),
    premium: Premium = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val premiumStatus by premium.status.collectAsStateWithLifecycle()
    val shareMessage = stringResource(Res.string.settings_share_message, AppPromotion.PLAY_STORE_URL)
    SettingsScaffold(title = stringResource(Res.string.settings_title), onBack = onBack) {
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_premium),
                subtitle = stringResource(
                    if (premiumStatus.isPremium) {
                        Res.string.settings_premium_sub_active
                    } else {
                        Res.string.settings_premium_sub
                    }
                ),
                icon = Icons.Rounded.WorkspacePremium,
                onClick = onPremiumClick,
            )
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_networks),
                subtitle = stringResource(Res.string.settings_networks_sub),
                icon = Icons.Rounded.Hub,
                onClick = onNetworksClick,
            )
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_guide),
                subtitle = stringResource(Res.string.settings_guide_sub),
                icon = Icons.AutoMirrored.Rounded.HelpOutline,
                onClick = onGuideClick,
            )
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_tour),
                subtitle = stringResource(Res.string.settings_tour_sub),
                icon = Icons.Rounded.Explore,
                onClick = {
                    viewModel.showTourAgain()
                    onBack()
                },
            )
        }
        item { HiddenTipsRow() }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_notifications),
                subtitle = stringResource(Res.string.settings_notifications_sub),
                icon = Icons.Rounded.Notifications,
                onClick = onNotificationsClick,
            )
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_appearance),
                subtitle = stringResource(Res.string.settings_appearance_sub),
                icon = Icons.Rounded.Palette,
                onClick = onAppearanceClick,
            )
        }
        if (state.canUseOwnTelegramKeys) {
            item {
                SettingsRow(
                    title = stringResource(Res.string.settings_telegram_keys),
                    subtitle = stringResource(
                        if (state.usesOwnTelegramKeys) {
                            Res.string.telegram_keys_using_own
                        } else {
                            Res.string.telegram_keys_using_builtin
                        }
                    ),
                    icon = Icons.Rounded.Key,
                    onClick = onTelegramKeysClick,
                )
            }
        }
        item { SettingsSectionHeader(stringResource(Res.string.settings_section_privacy)) }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_app_lock),
                subtitle = if (state.isLockEnabled) stringResource(Res.string.settings_app_lock_on) else stringResource(
                    Res.string.settings_off
                ),
                icon = Icons.Rounded.Lock,
                onClick = onAppLockClick,
            )
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_crash_reports),
                subtitle = stringResource(
                    if (state.hasCrashReport) Res.string.settings_crash_present else Res.string.settings_crash_none
                ),
                icon = Icons.Rounded.BugReport,
                onClick = onCrashReportsClick,
            )
        }
        item {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_ask_before_letting_in),
                subtitle = stringResource(Res.string.settings_ask_before_letting_in_sub),
                icon = Icons.Rounded.HowToReg,
                checked = state.asksBeforeLettingIn,
                onCheckedChange = viewModel::setAskBeforeLettingIn,
            )
        }
        if (state.canBackUpLogins) {
            item {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_backup_logins),
                    subtitle = stringResource(Res.string.settings_backup_logins_sub),
                    icon = Icons.Rounded.CloudUpload,
                    checked = state.isLoginBackupOn,
                    onCheckedChange = viewModel::setLoginBackup,
                )
            }
            if (state.isLoginBackupOn) {
                item {
                    SettingsRow(
                        title = stringResource(Res.string.settings_google_backup),
                        subtitle = state.lastBackupMillis?.let {
                            stringResource(
                                Res.string.settings_last_backup,
                                daysAgo(it)
                            )
                        }
                            ?: stringResource(Res.string.settings_not_backed_up),
                        icon = Icons.Rounded.SettingsBackupRestore,
                        onClick = viewModel::openBackupSettings,
                    )
                }
            }
        }
        item { SettingsSectionHeader(stringResource(Res.string.settings_section_about)) }
        if (promotion.isSupported) {
            item {
                SettingsRow(
                    title = stringResource(Res.string.settings_rate),
                    subtitle = stringResource(Res.string.settings_rate_sub),
                    icon = Icons.Rounded.StarRate,
                    onClick = promotion::openStorePage,
                )
            }
            item {
                SettingsRow(
                    title = stringResource(Res.string.settings_share),
                    subtitle = stringResource(Res.string.settings_share_sub),
                    icon = Icons.Rounded.Share,
                    onClick = { promotion.share(shareMessage) },
                )
            }
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_licenses),
                icon = Icons.Rounded.Gavel,
                onClick = onLicensesClick
            )
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_terms),
                subtitle = stringResource(Res.string.settings_terms_sub),
                icon = Icons.Rounded.Policy,
                onClick = onTermsClick,
            )
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_about),
                subtitle = stringResource(Res.string.settings_about_sub),
                icon = Icons.Rounded.Info,
                onClick = onAboutClick
            )
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_log_out),
                subtitle = stringResource(Res.string.settings_log_out_sub),
                icon = Icons.AutoMirrored.Rounded.Logout,
                onClick = viewModel::logOut
            )
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun daysAgo(millis: Long): String =
    when (val days = ((Clock.System.now().toEpochMilliseconds() - millis) / 86_400_000L).coerceAtLeast(0)) {
        0L -> stringResource(Res.string.settings_today)
        1L -> stringResource(Res.string.settings_yesterday)
        else -> pluralStringResource(Res.plurals.settings_days_ago, days.toInt(), days.toInt())
    }
