package com.devvalor.howyoudoing.networks.presentation.list

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.AccountLabel
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.presentation.util.UiText
import com.devvalor.howyoudoing.networks.domain.NetworkLogin

data class NetworksState(
    /** Networks whose how-to-connect tutorial the user chose not to see again. */
    val dismissedTutorials: Set<ChatNetwork> = emptySet(),
    /** Accepted once that networks may block accounts using an unofficial app. */
    val isRiskAccepted: Boolean = false,
    /** Networks connected from this phone (their first account), shown under each network. */
    val onDevice: Map<ChatNetwork, OnDeviceMessengerState> = emptyMap(),
    /** Every account slot on this phone, linked or not (see [AccountRef]). */
    val accounts: Map<AccountRef, OnDeviceMessengerState> = emptyMap(),
    /** The names and colours the user gave accounts. */
    val labels: Map<AccountRef, AccountLabel> = emptyMap(),
    /** An account just added next to another of the same network, to be named. */
    val naming: AccountRef? = null,
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

    /** [network]'s accounts logged in on this phone, first account first. */
    fun linkedAccounts(network: ChatNetwork): List<AccountRef> =
        accounts.filter { (account, state) -> account.network == network && state != OnDeviceMessengerState.NotLinked }
            .keys.sortedBy { it.slot }

    /** Whether [network] can have more than one account on this phone. */
    fun isMultiAccount(network: ChatNetwork): Boolean = accounts.keys.count { it.network == network } > 1

    /** The slot the next account of [network] would go in, or null when all are taken. */
    fun freeSlot(network: ChatNetwork): Int? =
        accounts.filter { (account, state) -> account.network == network && state == OnDeviceMessengerState.NotLinked }
            .keys.minOfOrNull { it.slot }

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
