package com.devvalor.howyoudoing.networks.presentation.navigation

import kotlinx.serialization.Serializable

sealed interface NetworksRoutes {
    @Serializable data object Graph : NetworksRoutes
    @Serializable data object List : NetworksRoutes

    /** @param network [com.devvalor.howyoudoing.core.domain.network.ChatNetwork] name */
    @Serializable data class Connect(val network: String) : NetworksRoutes
}
