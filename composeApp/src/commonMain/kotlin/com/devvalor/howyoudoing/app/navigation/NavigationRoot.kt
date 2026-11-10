package com.devvalor.howyoudoing.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.devvalor.howyoudoing.auth.presentation.navigation.AuthGraphRoutes
import com.devvalor.howyoudoing.auth.presentation.navigation.authGraph
import com.devvalor.howyoudoing.chat.presentation.navigation.ChatRoutes
import com.devvalor.howyoudoing.chat.presentation.navigation.chatGraph
import com.devvalor.howyoudoing.networks.presentation.history.EncryptedHistoryBannerRoot
import com.devvalor.howyoudoing.networks.presentation.navigation.NetworksRoutes
import com.devvalor.howyoudoing.networks.presentation.navigation.networksGraph
import com.devvalor.howyoudoing.settings.presentation.navigation.SettingsRoutes
import com.devvalor.howyoudoing.settings.presentation.navigation.settingsGraph

@Composable
fun NavigationRoot(
    navController: NavHostController,
    startDestination: Any,
) {
    // Screens switch at once, without the library's fades or the back gesture's shrinking page
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
        sizeTransform = null,
    ) {
        authGraph(
            onLoginSuccess = {
                navController.navigate(ChatRoutes.Graph) {
                    popUpTo(AuthGraphRoutes.Graph) { inclusive = true }
                }
            },
        )
        chatGraph(
            navController = navController,
            onNetworksClick = { troubleshoot ->
                if (troubleshoot == null) {
                    navController.navigate(NetworksRoutes.Graph) { launchSingleTop = true }
                } else {
                    navController.navigate(NetworksRoutes.List(troubleshoot = troubleshoot.name)) {
                        launchSingleTop = true
                    }
                }
            },
            onSettingsClick = {
                navController.navigate(SettingsRoutes.Graph) { launchSingleTop = true }
            },
            onGuideClick = {
                navController.navigate(SettingsRoutes.ConnectGuide) { launchSingleTop = true }
            },
            onPremiumClick = {
                navController.navigate(SettingsRoutes.Premium) { launchSingleTop = true }
            },
            inboxBanner = { encryptedNetworks -> EncryptedHistoryBannerRoot(encryptedNetworks) },
        )
        networksGraph(
            navController = navController,
            onTermsClick = { navController.navigate(SettingsRoutes.Terms) { launchSingleTop = true } },
            onPremiumClick = { navController.navigate(SettingsRoutes.Premium) { launchSingleTop = true } },
        )
        settingsGraph(
            navController = navController,
            onNetworksClick = { navController.navigate(NetworksRoutes.Graph) { launchSingleTop = true } },
        )
    }
}
