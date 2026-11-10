package com.devvalor.howyoudoing.networks.presentation.list

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.AccountLabel
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef

sealed interface NetworksAction {
    data object OnRefresh : NetworksAction
    /** Connect [network]; [slot] picks which of its accounts on the phone (0 the first, else another). */
    data class OnConnectClick(val network: ChatNetwork, val slot: Int = 0) : NetworksAction
    data class OnReconnectAccountClick(val account: AccountRef) : NetworksAction

    /** Rename or recolour an account; opens [NetworksState.naming]. */
    data class OnEditLabelClick(val account: AccountRef) : NetworksAction
    data class OnLabelSave(val account: AccountRef, val label: AccountLabel) : NetworksAction
    data object OnLabelDismiss : NetworksAction
    data object OnRiskAccepted : NetworksAction
    data class OnDisconnectClick(val network: ChatNetwork, val loginId: String) : NetworksAction
    data class OnDisconnectOnDeviceClick(val network: ChatNetwork, val slot: Int = 0) : NetworksAction
    data object OnBackClick : NetworksAction
}
