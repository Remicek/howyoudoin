package com.devvalor.howyoudoing.networks.presentation.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import androidx.navigation.toRoute
import com.devvalor.howyoudoing.networks.presentation.tutorial.TutorialRoot
import com.devvalor.howyoudoing.networks.presentation.connect.ConnectNetworkRoot
import com.devvalor.howyoudoing.networks.presentation.list.NetworksRoot

fun NavGraphBuilder.networksGraph(
    navController: NavController,
    onTermsClick: () -> Unit,
) {
    navigation<NetworksRoutes.Graph>(startDestination = NetworksRoutes.List()) {
        composable<NetworksRoutes.List> {
            NetworksRoot(
                onConnectClick = { network, slot ->
                    navController.navigate(NetworksRoutes.Connect(network.name, slot = slot)) {
                        launchSingleTop = true
                    }
                },
                onTutorialClick = { network ->
                    navController.navigate(NetworksRoutes.Tutorial(network.name)) { launchSingleTop = true }
                },
                onTermsClick = onTermsClick,
                onBack = { navController.navigateUp() },
            )
        }
        composable<NetworksRoutes.Tutorial> { backStackEntry ->
            val route = backStackEntry.toRoute<NetworksRoutes.Tutorial>()
            TutorialRoot(
                onFinish = { flowId ->
                    navController.navigate(NetworksRoutes.Connect(route.network, flowId)) {
                        popUpTo<NetworksRoutes.Tutorial> { inclusive = true }
                    }
                },
                onBack = { navController.navigateUp() },
            )
        }
        composable<NetworksRoutes.Connect> {
            ConnectNetworkRoot(
                onDone = { navController.popBackStack<NetworksRoutes.List>(inclusive = false) },
                onBack = { navController.navigateUp() },
            )
        }
    }
}
