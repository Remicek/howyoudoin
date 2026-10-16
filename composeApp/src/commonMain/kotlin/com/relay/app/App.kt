package com.relay.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.relay.app.navigation.NavigationRoot
import com.relay.auth.presentation.navigation.AuthGraphRoutes
import com.relay.chat.presentation.navigation.ChatRoutes
import com.relay.core.designsystem.theme.RelayTheme
import com.relay.core.presentation.util.ObserveAsEvents
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun App(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    viewModel: MainViewModel = koinViewModel()
) {
    val navController = rememberNavController()
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            MainEvent.OnSessionEnded -> {
                navController.navigate(AuthGraphRoutes.Graph) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    RelayTheme(darkTheme = isDarkTheme) {
        if (!state.isCheckingAuth) {
            NavigationRoot(
                navController = navController,
                startDestination = if (state.isLoggedIn) ChatRoutes.Graph else AuthGraphRoutes.Graph
            )
        }
    }
}
