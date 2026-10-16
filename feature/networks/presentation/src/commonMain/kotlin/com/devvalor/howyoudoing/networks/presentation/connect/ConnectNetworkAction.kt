package com.devvalor.howyoudoing.networks.presentation.connect

sealed interface ConnectNetworkAction {
    data class OnBridgeUrlChange(val url: String) : ConnectNetworkAction
    data object OnSaveBridgeUrlClick : ConnectNetworkAction
    data object OnEditBridgeUrlClick : ConnectNetworkAction
    data class OnFlowClick(val flowId: String) : ConnectNetworkAction
    data class OnInputChange(val fieldId: String, val value: String) : ConnectNetworkAction
    data object OnSubmitInputClick : ConnectNetworkAction
    data class OnCookiesCaptured(val cookies: Map<String, String>) : ConnectNetworkAction
    data object OnRetryClick : ConnectNetworkAction
    data object OnDoneClick : ConnectNetworkAction
    data object OnBackClick : ConnectNetworkAction
}
