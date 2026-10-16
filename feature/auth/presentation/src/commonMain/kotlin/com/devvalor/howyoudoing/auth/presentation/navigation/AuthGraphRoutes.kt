package com.devvalor.howyoudoing.auth.presentation.navigation

import kotlinx.serialization.Serializable

sealed interface AuthGraphRoutes {
    @Serializable data object Graph : AuthGraphRoutes
    @Serializable data object Login : AuthGraphRoutes
}
