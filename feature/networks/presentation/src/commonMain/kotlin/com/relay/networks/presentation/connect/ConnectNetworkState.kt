package com.relay.networks.presentation.connect

import com.relay.core.domain.network.ChatNetwork
import com.relay.core.presentation.util.UiText
import com.relay.networks.domain.BridgeLoginFlow
import com.relay.networks.domain.BridgeLoginStep

data class ConnectNetworkState(
    val network: ChatNetwork,
    val phase: ConnectPhase = ConnectPhase.Loading,
    val bridgeUrl: String = "",
    val inputValues: Map<String, String> = emptyMap(),
    val isSubmitting: Boolean = false,
    val error: UiText? = null
) {
    val canSubmitInput: Boolean
        get() {
            val step = (phase as? ConnectPhase.Step)?.step as? BridgeLoginStep.UserInput ?: return false
            return !isSubmitting && step.fields.all { !inputValues[it.id].isNullOrBlank() }
        }
}

sealed interface ConnectPhase {
    data object Loading : ConnectPhase
    data object ConfigureBridge : ConnectPhase
    data class ChooseFlow(val flows: List<BridgeLoginFlow>) : ConnectPhase
    data class Step(val step: BridgeLoginStep) : ConnectPhase
    data class Connected(val accountName: String?) : ConnectPhase
}
