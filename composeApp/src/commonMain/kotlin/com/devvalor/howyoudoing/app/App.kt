package com.devvalor.howyoudoing.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.devvalor.howyoudoing.app.notifications.NotificationRouter
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.devvalor.howyoudoing.app.navigation.NavigationRoot
import com.devvalor.howyoudoing.auth.presentation.navigation.AuthGraphRoutes
import com.devvalor.howyoudoing.chat.presentation.navigation.ChatRoutes
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinTheme
import com.devvalor.howyoudoing.core.presentation.util.ObserveAsEvents
import com.devvalor.howyoudoing.settings.presentation.lock.AppLockGate
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun App(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    viewModel: MainViewModel = koinViewModel()
) {
    val navController = rememberNavController()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A tapped notification opens its chat (once signed in)
    val pendingChatId by NotificationRouter.pendingChatId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingChatId, state.isLoggedIn, state.isCheckingAuth) {
        val chatId = pendingChatId ?: return@LaunchedEffect
        if (state.isCheckingAuth || !state.isLoggedIn) return@LaunchedEffect
        NotificationRouter.pendingChatId.value = null
        navController.navigate(ChatRoutes.Conversation(chatId)) { launchSingleTop = true }
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            MainEvent.OnSessionEnded -> {
                navController.navigate(AuthGraphRoutes.Graph) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    HowYouDoinTheme(darkTheme = isDarkTheme) {
        if (!state.isCheckingAuth) {
            // The PIN / fingerprint lock guards everything once signed in
            AppLockGate(isActive = state.isLoggedIn) {
                NavigationRoot(
                    navController = navController,
                    startDestination = if (state.isLoggedIn) ChatRoutes.Graph else AuthGraphRoutes.Graph
                )
            }
        }
    }
}
