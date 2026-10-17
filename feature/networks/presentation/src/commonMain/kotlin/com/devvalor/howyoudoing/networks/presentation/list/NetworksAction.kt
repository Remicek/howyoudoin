package com.devvalor.howyoudoing.networks.presentation.list

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

sealed interface NetworksAction {
    data object OnRefresh : NetworksAction
    data class OnConnectClick(val network: ChatNetwork) : NetworksAction
    data class OnDisconnectClick(val network: ChatNetwork, val loginId: String) : NetworksAction
    data class OnDisconnectOnDeviceClick(val network: ChatNetwork) : NetworksAction
    data object OnBackClick : NetworksAction
}
