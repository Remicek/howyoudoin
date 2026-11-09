package com.devvalor.howyoudoing.app

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
import com.devvalor.howyoudoing.settings.presentation.appearance.isDark
import org.koin.compose.viewmodel.koinViewModel
import com.devvalor.howyoudoing.app.backup.BackupOfferRoot
import com.devvalor.howyoudoing.app.background.BackgroundOfferRoot
import com.devvalor.howyoudoing.app.account.AccountActionRoot
import com.devvalor.howyoudoing.app.captcha.CaptchaRoot
import com.devvalor.howyoudoing.app.backup.RestoreLoginsScreen
import com.devvalor.howyoudoing.app.backup.RestoreLoginsViewModel
import com.devvalor.howyoudoing.networks.presentation.navigation.NetworksRoutes

/** [onDarkThemeChange] tells the platform whether the app is drawn dark, for the colour of the status bar icons. */
@Composable
fun App(
    onDarkThemeChange: (Boolean) -> Unit = {},
    viewModel: MainViewModel = koinViewModel(),
    restoreViewModel: RestoreLoginsViewModel = koinViewModel(),
    appearanceViewModel: AppearanceViewModel = koinViewModel()
) {
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

    SavedTheme(appearanceViewModel, onDarkThemeChange) {
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
                    // A network asking for a CAPTCHA (Discord, for a friend request)
                    CaptchaRoot()
                    // A network waiting for the user on its website (Facebook's cookie choices), opened by Reconnect
                    AccountActionRoot()
                    // Once a network is connected: ask to keep running in the background
                    BackgroundOfferRoot()
                }
            }
        }
    }
}

/** The look picked in Settings → Appearance. Nothing is drawn until it's read, so the default doesn't flash first. */
@Composable
private fun SavedTheme(
    appearance: AppearanceViewModel,
    onDarkThemeChange: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val variant by appearance.variant.collectAsStateWithLifecycle()
    val darkMode by appearance.darkMode.collectAsStateWithLifecycle()
    val isDark = darkMode?.isDark() ?: return
    LaunchedEffect(isDark) { onDarkThemeChange(isDark) }
    HowYouDoinTheme(darkTheme = isDark, variant = variant ?: return, content = content)
}
