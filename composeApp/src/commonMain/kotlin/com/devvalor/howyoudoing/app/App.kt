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
import com.devvalor.howyoudoing.settings.presentation.lock.AppLockOfferRoot
import com.devvalor.howyoudoing.settings.presentation.navigation.SettingsRoutes
import com.devvalor.howyoudoing.settings.presentation.appearance.AppearanceViewModel
import org.koin.compose.viewmodel.koinViewModel
import com.devvalor.howyoudoing.app.backup.BackupOfferRoot
import com.devvalor.howyoudoing.app.backup.RestoreLoginsScreen
import com.devvalor.howyoudoing.app.backup.RestoreLoginsViewModel
import com.devvalor.howyoudoing.networks.presentation.navigation.NetworksRoutes

@Composable
fun App(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    viewModel: MainViewModel = koinViewModel(),
    restoreViewModel: RestoreLoginsViewModel = koinViewModel(),
    appearanceViewModel: AppearanceViewModel = koinViewModel()
) {
    val themeVariant by appearanceViewModel.variant.collectAsStateWithLifecycle()
    val restore by restoreViewModel.state.collectAsStateWithLifecycle()
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

    // A connection-problem notification opens Networks to fix it
    val pendingNetworks by NotificationRouter.pendingNetworks.collectAsStateWithLifecycle()
    LaunchedEffect(pendingNetworks, state.isLoggedIn, state.isCheckingAuth) {
        if (!pendingNetworks || state.isCheckingAuth || !state.isLoggedIn) return@LaunchedEffect
        NotificationRouter.pendingNetworks.value = false
        navController.navigate(NetworksRoutes.Graph) { launchSingleTop = true }
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

    // Nothing is drawn until the saved look is read, so it doesn't flash the default one first
    val variant = themeVariant ?: return
    HowYouDoinTheme(darkTheme = isDarkTheme, variant = variant) {
        // Logins found in the phone's backup are offered before anything else (a reinstall or a new phone)
        if (restore.isVisible) {
            RestoreLoginsScreen(
                state = restore,
                onToggle = restoreViewModel::toggle,
                onRestore = restoreViewModel::restore,
                onLater = restoreViewModel::later,
                onDiscard = restoreViewModel::discard,
                onClose = restoreViewModel::close
            )
        } else if (!state.isCheckingAuth && !restore.isChecking) {
            // The PIN / fingerprint lock guards everything once signed in
            AppLockGate(isActive = state.isLoggedIn) {
                NavigationRoot(
                    navController = navController,
                    startDestination = if (state.isLoggedIn) ChatRoutes.Graph else AuthGraphRoutes.Graph
                )
                // Once after signing in: offer the lock, which stays off unless chosen
                if (state.isLoggedIn) {
                    AppLockOfferRoot(onSetUp = {
                        navController.navigate(SettingsRoutes.AppLockSetup) { launchSingleTop = true }
                    })
                    // Once two networks are connected: offer to back up the logins
                    BackupOfferRoot()
                }
            }
        }
    }
}
