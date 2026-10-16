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
import com.devvalor.howyoudoing.networks.domain.InputFieldType
import com.devvalor.howyoudoing.networks.presentation.components.DeveloperOptions
import com.devvalor.howyoudoing.networks.presentation.components.phone.Country
import com.devvalor.howyoudoing.networks.presentation.components.phone.DeviceRegionProvider
import com.devvalor.howyoudoing.networks.presentation.components.phone.PhoneNumbers
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
    private val bridgeRepository: BridgeRepository,
    private val deviceRegion: DeviceRegionProvider,
    developerOptions: DeveloperOptions
) : ViewModel() {

    private val route = savedStateHandle.toRoute<NetworksRoutes.Connect>()
    private val network = ChatNetwork.valueOf(route.network)

    /** Flow chosen in the tutorial; consumed once so "Try again" shows the chooser. */
    private var preselectedFlowId: String? = route.flowId

    private var hasLoadedInitialData = false
    private var stepJob: Job? = null

    private val initialState = ConnectNetworkState(network = network, canEditBridgeUrl = developerOptions.isEnabled())
    private val _state = MutableStateFlow(initialState)
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
            initialValue = initialState
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
            is ConnectNetworkAction.OnInputChange -> onInputChange(action.fieldId, action.value)
            is ConnectNetworkAction.OnCountrySelect -> {
                _state.update { it.copy(phoneCountries = it.phoneCountries + (action.fieldId to action.isoCode)) }
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
                is Result.Success -> {
                    _state.update { it.copy(phase = ConnectPhase.ChooseFlow(result.data.flows)) }
                    val preselected = preselectedFlowId?.takeIf { id -> result.data.flows.any { it.id == id } }
                    preselectedFlowId = null
                    if (preselected != null) advance(bridgeRepository.startLogin(network, preselected))
                }
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
        val values = step.fields.associate { it.id to state.value.submittedValue(it) }
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
            advance(request())
        }
    }

    /** Shows [first] and keeps going through display-and-wait steps until input or completion. */
    private suspend fun advance(first: Result<BridgeLoginStep, DataError.Remote>) {
        var result = first
        while (true) {
            val step = when (val current = result) {
                is Result.Failure -> {
                    _state.update { it.copy(isSubmitting = false, error = current.error.toUiText()) }
                    return
                }
                is Result.Success -> current.data
            }
            showStep(step)
            if (step !is BridgeLoginStep.DisplayAndWait) return

            // The bridge answers once the user scanned / entered the code, or with a refreshed QR.
            result = bridgeRepository.waitForStep(network, step)
            while (result is Result.Failure && result.error == DataError.Remote.REQUEST_TIMEOUT) {
                result = bridgeRepository.waitForStep(network, step)
            }
        }
    }

    /** A pasted "+48 600 …" selects Poland and keeps only the national part in the field. */
    private fun onInputChange(fieldId: String, value: String) {
        val step = (state.value.phase as? ConnectPhase.Step)?.step as? BridgeLoginStep.UserInput
        val isPhone = step?.fields?.firstOrNull { it.id == fieldId }?.type == InputFieldType.PHONE_NUMBER
        val international = if (isPhone) PhoneNumbers.parseInternational(value) else null
        _state.update { state ->
            if (international == null) {
                state.copy(inputValues = state.inputValues + (fieldId to value))
            } else {
                val (country, national) = international
                state.copy(
                    inputValues = state.inputValues + (fieldId to national),
                    phoneCountries = state.phoneCountries + (fieldId to country.isoCode)
                )
            }
        }
    }

    /** SIM / network country where available, e.g. PL for a Polish SIM even if the UI language is en-US. */
    private fun defaultCountry(): Country =
        Country.forIso(deviceRegion.countryIso()) ?: Country.forIso("US")!!

    /** Replaces the login ID with the account's display name ("+48 600…", "Remik Kowalski"). */
    private fun loadConnectedAccountName(loginId: String?) {
        viewModelScope.launch {
            val logins = (bridgeRepository.getStatus(network) as? Result.Success)?.data?.logins.orEmpty()
            val name = logins.firstOrNull { it.id == loginId }?.name ?: logins.singleOrNull()?.name
            if (name != null && name != loginId) {
                _state.update { state ->
                    if (state.phase is ConnectPhase.Connected) state.copy(phase = ConnectPhase.Connected(name)) else state
                }
            }
        }
    }

    private fun showStep(step: BridgeLoginStep) {
        if (step is BridgeLoginStep.Complete) loadConnectedAccountName(step.userLoginId)
        _state.update { state ->
            when (step) {
                // The login ID is an internal number (e.g. a Facebook user ID); the name is looked up below
                is BridgeLoginStep.Complete -> state.copy(
                    phase = ConnectPhase.Connected(accountName = null),
                    isSubmitting = false
                )
                is BridgeLoginStep.UserInput -> state.copy(
                    phase = ConnectPhase.Step(step),
                    inputValues = step.fields.mapNotNull { field -> field.defaultValue?.let { field.id to it } }.toMap(),
                    phoneCountries = step.fields
                        .filter { it.type == InputFieldType.PHONE_NUMBER }
                        .associate { it.id to defaultCountry().isoCode },
                    isSubmitting = false
                )
                else -> state.copy(phase = ConnectPhase.Step(step), isSubmitting = false)
            }
        }
    }
}
