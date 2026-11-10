package com.devvalor.howyoudoing.settings.presentation.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.devvalor.howyoudoing.settings.presentation.about.AboutRoot
import com.devvalor.howyoudoing.settings.presentation.appearance.AppearanceRoot
import com.devvalor.howyoudoing.settings.presentation.crash.CrashReportsRoot
import com.devvalor.howyoudoing.settings.presentation.guide.ConnectGuideRoot
import com.devvalor.howyoudoing.settings.presentation.licenses.LicensesRoot
import com.devvalor.howyoudoing.settings.presentation.lock.AppLockSettingsRoot
import com.devvalor.howyoudoing.settings.presentation.main.SettingsRoot
import com.devvalor.howyoudoing.settings.presentation.premium.PremiumRoot
import com.devvalor.howyoudoing.settings.presentation.telegram.TelegramKeysRoot
import com.devvalor.howyoudoing.settings.presentation.terms.TermsRoot
import kotlinx.serialization.Serializable

sealed interface SettingsRoutes {
    @Serializable data object Graph : SettingsRoutes
    @Serializable data object Main : SettingsRoutes
    @Serializable data object AppLock : SettingsRoutes
    /** Choosing a PIN straight away, from the prompt after signing in. */
    @Serializable data object AppLockSetup : SettingsRoutes
    @Serializable data object CrashReports : SettingsRoutes
    @Serializable data object Appearance : SettingsRoutes
    @Serializable data object Licenses : SettingsRoutes
    @Serializable data object About : SettingsRoutes
    /** Disclaimer: unofficial, "as is", accounts may be blocked. */
    @Serializable data object Terms : SettingsRoutes
    /** Which login to use for each network. */
    @Serializable data object ConnectGuide : SettingsRoutes
    /** The user's own Telegram API keys. */
    @Serializable data object TelegramKeys : SettingsRoutes
    /** The plans: call minutes left, Premium and top-ups. */
    @Serializable data object Premium : SettingsRoutes
}

fun NavGraphBuilder.settingsGraph(
    navController: NavController,
    onNetworksClick: () -> Unit,
) {
    fun open(route: SettingsRoutes) = navController.navigate(route) { launchSingleTop = true }
    navigation<SettingsRoutes.Graph>(startDestination = SettingsRoutes.Main) {
        composable<SettingsRoutes.Main> {
            SettingsRoot(
                onNetworksClick = onNetworksClick,
                onAppLockClick = { open(SettingsRoutes.AppLock) },
                onCrashReportsClick = { open(SettingsRoutes.CrashReports) },
                onAppearanceClick = { open(SettingsRoutes.Appearance) },
                onLicensesClick = { open(SettingsRoutes.Licenses) },
                onAboutClick = { open(SettingsRoutes.About) },
                onTermsClick = { open(SettingsRoutes.Terms) },
                onGuideClick = { open(SettingsRoutes.ConnectGuide) },
                onTelegramKeysClick = { open(SettingsRoutes.TelegramKeys) },
                onPremiumClick = { open(SettingsRoutes.Premium) },
                onBack = { navController.navigateUp() },
            )
        }
        composable<SettingsRoutes.AppLock> { AppLockSettingsRoot(onBack = { navController.navigateUp() }) }
        composable<SettingsRoutes.AppLockSetup> {
            AppLockSettingsRoot(onBack = { navController.navigateUp() }, startSetup = true)
        }
        composable<SettingsRoutes.CrashReports> { CrashReportsRoot(onBack = { navController.navigateUp() }) }
        composable<SettingsRoutes.Appearance> { AppearanceRoot(onBack = { navController.navigateUp() }) }
        composable<SettingsRoutes.Licenses> { LicensesRoot(onBack = { navController.navigateUp() }) }
        composable<SettingsRoutes.ConnectGuide> {
            ConnectGuideRoot(onNetworksClick = onNetworksClick, onBack = { navController.navigateUp() })
        }
        composable<SettingsRoutes.About> {
            AboutRoot(
                onLicensesClick = { open(SettingsRoutes.Licenses) },
                onTermsClick = { open(SettingsRoutes.Terms) },
                onBack = { navController.navigateUp() },
            )
        }
        composable<SettingsRoutes.Terms> { TermsRoot(onBack = { navController.navigateUp() }) }
        composable<SettingsRoutes.TelegramKeys> { TelegramKeysRoot(onBack = { navController.navigateUp() }) }
        composable<SettingsRoutes.Premium> { PremiumRoot(onBack = { navController.navigateUp() }) }
    }
}
