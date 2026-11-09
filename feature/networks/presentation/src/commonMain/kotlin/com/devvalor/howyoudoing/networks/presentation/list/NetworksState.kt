package com.devvalor.howyoudoing.networks.presentation.list

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.presentation.util.UiText
import com.devvalor.howyoudoing.networks.domain.NetworkLogin

data class NetworksState(
    /** Networks whose how-to-connect tutorial the user chose not to see again. */
    val dismissedTutorials: Set<ChatNetwork> = emptySet(),
    /** Accepted once that networks may block accounts using an unofficial app. */
    val isRiskAccepted: Boolean = false,
    /** Networks connected from this phone (experimental), shown under each network. */
    val onDevice: Map<ChatNetwork, OnDeviceMessengerState> = emptyMap(),
    /** Opened from the inbox because reconnecting didn't help: shown first, with how to fix it. */
    val troubleshoot: ChatNetwork? = null,
    val items: List<NetworkItemUi> = ChatNetwork.connectable.map { network ->
        NetworkItemUi(
            network = network,
            status = if (network.isSupported) NetworkStatus.Loading else NetworkStatus.ComingSoon
        )
    }
) {
    /**
     * Matrix is an open protocol made for other apps, so only the other networks carry the risk. Discord bans
     * accounts for it outright, so it's warned about every time.
     */
    fun shouldWarnAboutRisk(network: ChatNetwork): Boolean =
        network.isBridged && (!isRiskAccepted || network == ChatNetwork.DISCORD)

    fun shouldShowTutorial(network: ChatNetwork): Boolean =
        network in TUTORIAL_NETWORKS && network !in dismissedTutorials

    companion object {
        /** Networks that have a tutorial (not Messenger yet: its login steps still change). */
        val TUTORIAL_NETWORKS = setOf(ChatNetwork.WHATSAPP)
    }
}

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
