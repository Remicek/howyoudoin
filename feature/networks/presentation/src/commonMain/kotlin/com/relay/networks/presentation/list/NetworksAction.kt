package com.relay.networks.presentation.list

import com.relay.core.domain.network.ChatNetwork

sealed interface NetworksAction {
    data object OnRefresh : NetworksAction
    data class OnConnectClick(val network: ChatNetwork) : NetworksAction
    data class OnDisconnectClick(val network: ChatNetwork, val loginId: String) : NetworksAction
    data object OnBackClick : NetworksAction
}
