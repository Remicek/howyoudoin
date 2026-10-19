package com.devvalor.howyoudoing.settings.presentation.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.devvalor.howyoudoing.settings.presentation.about.AboutRoot
import com.devvalor.howyoudoing.settings.presentation.crash.CrashReportsRoot
import com.devvalor.howyoudoing.settings.presentation.licenses.LicensesRoot
import com.devvalor.howyoudoing.settings.presentation.lock.AppLockSettingsRoot
import com.devvalor.howyoudoing.settings.presentation.main.SettingsRoot
import kotlinx.serialization.Serializable

sealed interface SettingsRoutes {
    @Serializable data object Graph : SettingsRoutes
    @Serializable data object Main : SettingsRoutes
    @Serializable data object AppLock : SettingsRoutes
    @Serializable data object CrashReports : SettingsRoutes
    @Serializable data object Licenses : SettingsRoutes
    @Serializable data object About : SettingsRoutes
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
                onLicensesClick = { open(SettingsRoutes.Licenses) },
                onAboutClick = { open(SettingsRoutes.About) },
                onBack = { navController.navigateUp() },
            )
        }
        composable<SettingsRoutes.AppLock> { AppLockSettingsRoot(onBack = { navController.navigateUp() }) }
        composable<SettingsRoutes.CrashReports> { CrashReportsRoot(onBack = { navController.navigateUp() }) }
        composable<SettingsRoutes.Licenses> { LicensesRoot(onBack = { navController.navigateUp() }) }
        composable<SettingsRoutes.About> {
            AboutRoot(onLicensesClick = { open(SettingsRoutes.Licenses) }, onBack = { navController.navigateUp() })
        }
    }
}
