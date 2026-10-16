package com.devvalor.howyoudoing.networks.presentation.connect

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.presentation.util.toUiText
import com.devvalor.howyoudoing.networks.domain.BridgeLoginStep
import com.devvalor.howyoudoing.networks.domain.BridgeRepository
import com.devvalor.howyoudoing.networks.presentation.navigation.NetworksRoutes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ConnectNetworkViewModel(
    savedStateHandle: SavedStateHandle,
    private val bridgeRepository: BridgeRepository
) : ViewModel() {

    private val network = ChatNetwork.valueOf(savedStateHandle.toRoute<NetworksRoutes.Connect>().network)

    private var hasLoadedInitialData = false
    private var stepJob: Job? = null

    private val _state = MutableStateFlow(ConnectNetworkState(network = network))
    val state = _state
        .onStart {
            if (!hasLoadedInitialData) {
                loadFlows()
                hasLoadedInitialData = true
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = ConnectNetworkState(network = network)
        )

    fun onAction(action: ConnectNetworkAction) {
        when (action) {
            is ConnectNetworkAction.OnBridgeUrlChange -> _state.update { it.copy(bridgeUrl = action.url, error = null) }
            ConnectNetworkAction.OnSaveBridgeUrlClick -> saveBridgeUrl()
            ConnectNetworkAction.OnEditBridgeUrlClick -> {
                stepJob?.cancel()
                _state.update { it.copy(phase = ConnectPhase.ConfigureBridge, error = null) }
            }
            is ConnectNetworkAction.OnFlowClick -> runStep { bridgeRepository.startLogin(network, action.flowId) }
            is ConnectNetworkAction.OnInputChange -> {
                _state.update { it.copy(inputValues = it.inputValues + (action.fieldId to action.value)) }
            }
            ConnectNetworkAction.OnSubmitInputClick -> submitInput()
            is ConnectNetworkAction.OnCookiesCaptured -> submitCookies(action.cookies)
            ConnectNetworkAction.OnRetryClick -> loadFlows()
            else -> Unit
        }
    }

    private fun loadFlows() {
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            _state.update { it.copy(phase = ConnectPhase.Loading, error = null, isSubmitting = false) }
            if (!bridgeRepository.observeIsConfigured(network).first()) {
                val savedUrl = bridgeRepository.observeBridgeUrl(network).first().orEmpty()
                _state.update { it.copy(phase = ConnectPhase.ConfigureBridge, bridgeUrl = savedUrl) }
                return@launch
            }
            when (val result = bridgeRepository.getStatus(network)) {
                is Result.Success -> _state.update { it.copy(phase = ConnectPhase.ChooseFlow(result.data.flows)) }
                is Result.Failure -> _state.update {
                    it.copy(phase = ConnectPhase.ChooseFlow(emptyList()), error = result.error.toUiText())
                }
            }
        }
    }

    private fun saveBridgeUrl() {
        val url = state.value.bridgeUrl.trim()
        if (url.isEmpty()) return
        viewModelScope.launch {
            bridgeRepository.setBridgeUrl(network, url)
            loadFlows()
        }
    }

    private fun submitInput() {
        val step = (state.value.phase as? ConnectPhase.Step)?.step as? BridgeLoginStep.UserInput ?: return
        if (!state.value.canSubmitInput) return
        val values = step.fields.associate { it.id to state.value.inputValues[it.id].orEmpty().trim() }
        runStep { bridgeRepository.submitUserInput(network, step, values) }
    }

    private fun submitCookies(cookiesByName: Map<String, String>) {
        val step = (state.value.phase as? ConnectPhase.Step)?.step as? BridgeLoginStep.Cookies ?: return
        if (state.value.isSubmitting) return
        val values = step.cookies
            .mapNotNull { cookie -> cookiesByName[cookie.cookieName]?.let { cookie.fieldId to it } }
            .toMap()
        runStep { bridgeRepository.submitCookies(network, step, values) }
    }

    /** Runs a request that yields the next login step and moves the UI to it. */
    private fun runStep(request: suspend () -> Result<BridgeLoginStep, DataError.Remote>) {
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            var result = request()
            while (true) {
                val step = when (val current = result) {
                    is Result.Failure -> {
                        _state.update { it.copy(isSubmitting = false, error = current.error.toUiText()) }
                        return@launch
                    }
                    is Result.Success -> current.data
                }
                showStep(step)
                if (step !is BridgeLoginStep.DisplayAndWait) return@launch

                // The bridge answers once the user scanned / entered the code, or with a refreshed QR.
                result = bridgeRepository.waitForStep(network, step)
                while (result is Result.Failure && result.error == DataError.Remote.REQUEST_TIMEOUT) {
                    result = bridgeRepository.waitForStep(network, step)
                }
            }
        }
    }

    private fun showStep(step: BridgeLoginStep) {
        _state.update { state ->
            when (step) {
                is BridgeLoginStep.Complete -> state.copy(
                    phase = ConnectPhase.Connected(step.userLoginId),
                    isSubmitting = false
                )
                is BridgeLoginStep.UserInput -> state.copy(
                    phase = ConnectPhase.Step(step),
                    inputValues = step.fields.mapNotNull { field -> field.defaultValue?.let { field.id to it } }.toMap(),
                    isSubmitting = false
                )
                else -> state.copy(phase = ConnectPhase.Step(step), isSubmitting = false)
            }
        }
    }
}
