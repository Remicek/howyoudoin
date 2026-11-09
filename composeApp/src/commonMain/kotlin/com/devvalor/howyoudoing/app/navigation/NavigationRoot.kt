package com.devvalor.howyoudoing.app.navigation

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
    NavHost(
        navController = navController,
        startDestination = startDestination,
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
            onNetworksClick = {
                navController.navigate(NetworksRoutes.Graph) { launchSingleTop = true }
            },
            onSettingsClick = {
                navController.navigate(SettingsRoutes.Graph) { launchSingleTop = true }
            },
            onGuideClick = {
                navController.navigate(SettingsRoutes.ConnectGuide) { launchSingleTop = true }
            },
            onFixConnectionClick = { network ->
                navController.navigate(NetworksRoutes.List(troubleshoot = network.name)) { launchSingleTop = true }
            },
            inboxBanner = { encryptedNetworks -> EncryptedHistoryBannerRoot(encryptedNetworks) },
        )
        networksGraph(
            navController = navController,
            onTermsClick = { navController.navigate(SettingsRoutes.Terms) { launchSingleTop = true } },
        )
        settingsGraph(
            navController = navController,
            onNetworksClick = { navController.navigate(NetworksRoutes.Graph) { launchSingleTop = true } },
        )
    }
}
