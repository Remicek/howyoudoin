package com.devvalor.howyoudoing.networks.presentation.navigation

import kotlinx.serialization.Serializable

sealed interface NetworksRoutes {
    @Serializable data object Graph : NetworksRoutes
    /** @param troubleshoot [com.devvalor.howyoudoing.core.domain.network.ChatNetwork] name to show how to fix first */
    @Serializable data class List(val troubleshoot: String? = null) : NetworksRoutes

    /**
     * @param network [com.devvalor.howyoudoing.core.domain.network.ChatNetwork] name
     * @param flowId login flow to start right away (e.g. picked in the tutorial), null shows the chooser
     */
    @Serializable data class Connect(val network: String, val flowId: String? = null) : NetworksRoutes

    /** How-to-connect onboarding shown before the first connection to [network]. */
    @Serializable data class Tutorial(val network: String) : NetworksRoutes
}
