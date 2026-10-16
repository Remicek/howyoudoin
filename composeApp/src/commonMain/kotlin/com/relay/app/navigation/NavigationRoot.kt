package com.relay.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.relay.auth.presentation.navigation.AuthGraphRoutes
import com.relay.auth.presentation.navigation.authGraph
import com.relay.chat.presentation.navigation.ChatRoutes
import com.relay.chat.presentation.navigation.chatGraph
import com.relay.networks.presentation.navigation.NetworksRoutes
import com.relay.networks.presentation.navigation.networksGraph

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
        )
        networksGraph(navController = navController)
    }
}
