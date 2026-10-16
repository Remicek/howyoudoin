package com.relay.auth.presentation.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.relay.auth.presentation.login.LoginRoot

fun NavGraphBuilder.authGraph(
    onLoginSuccess: () -> Unit,
) {
    navigation<AuthGraphRoutes.Graph>(startDestination = AuthGraphRoutes.Login) {
        composable<AuthGraphRoutes.Login> {
            LoginRoot(onLoginSuccess = onLoginSuccess)
        }
    }
}
