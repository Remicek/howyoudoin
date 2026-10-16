package com.relay.networks.presentation.list

import com.relay.core.domain.network.ChatNetwork
import com.relay.core.presentation.util.UiText
import com.relay.networks.domain.NetworkLogin

data class NetworksState(
    val items: List<NetworkItemUi> = ChatNetwork.bridged.map { network ->
        NetworkItemUi(
            network = network,
            status = if (network.isSupported) NetworkStatus.Loading else NetworkStatus.ComingSoon
        )
    }
)

data class NetworkItemUi(
    val network: ChatNetwork,
    val status: NetworkStatus
)

sealed interface NetworkStatus {
    data object Loading : NetworkStatus
    data object ComingSoon : NetworkStatus
    data object NotConfigured : NetworkStatus
    data class Unavailable(val error: UiText) : NetworkStatus
    data class Ready(val logins: List<NetworkLogin>) : NetworkStatus
}
