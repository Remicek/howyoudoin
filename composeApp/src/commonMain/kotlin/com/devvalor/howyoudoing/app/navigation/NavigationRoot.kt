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
            inboxBanner = { encryptedNetworks -> EncryptedHistoryBannerRoot(encryptedNetworks) },
        )
        networksGraph(navController = navController)
    }
}
