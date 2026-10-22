package com.devvalor.howyoudoing.networks.presentation.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceInstagram
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceSignal
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceSlack
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMatrix
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceTelegram
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.domain.util.onFailure
import com.devvalor.howyoudoing.core.domain.util.onSuccess
import com.devvalor.howyoudoing.core.presentation.util.toUiText
import com.devvalor.howyoudoing.networks.domain.BridgeRepository
import com.devvalor.howyoudoing.networks.domain.OnboardingRepository
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NetworksViewModel(
    private val bridgeRepository: BridgeRepository,
    onboardingRepository: OnboardingRepository,
    onDeviceMessenger: OnDeviceMessenger,
    onDeviceWhatsApp: OnDeviceWhatsApp,
    onDeviceInstagram: OnDeviceInstagram,
    onDeviceTelegram: OnDeviceTelegram,
    onDeviceSignal: OnDeviceSignal,
    onDeviceSlack: OnDeviceSlack,
    onDeviceMatrix: OnDeviceMatrix,
    private val connectionMonitor: ConnectionMonitor
) : ViewModel() {

    private val onDeviceClients =
        listOf(onDeviceMessenger, onDeviceWhatsApp, onDeviceInstagram, onDeviceTelegram, onDeviceSignal, onDeviceSlack, onDeviceMatrix)
            .associateBy { it.network }

    private val eventChannel = Channel<NetworksEvent>()
    val events = eventChannel.receiveAsFlow()

    private val _state = MutableStateFlow(NetworksState())
    val state = _state.asStateFlow()

    init {
        onboardingRepository.observeDismissedTutorials()
            .onEach { dismissed -> _state.update { it.copy(dismissedTutorials = dismissed) } }
            .launchIn(viewModelScope)
        onDeviceClients.values.forEach { client ->
            client.state
                .onEach { onDevice -> _state.update { it.copy(onDevice = it.onDevice + (client.network to onDevice)) } }
                .launchIn(viewModelScope)
        }
    }

    fun onAction(action: NetworksAction) {
        when (action) {
            NetworksAction.OnRefresh -> refresh()
            is NetworksAction.OnDisconnectClick -> disconnect(action.network, action.loginId)
            is NetworksAction.OnDisconnectOnDeviceClick -> viewModelScope.launch {
                connectionMonitor.expectDisconnect(action.network)
                onDeviceClients[action.network]?.unlink()
            }
            else -> Unit
        }
    }

    private fun refresh() {
        ChatNetwork.bridged.filter { it.isSupported }.forEach { network ->
            viewModelScope.launch { loadStatus(network) }
        }
        // No server bridge, but connectable on the device
        ChatNetwork.connectable
            .filter { !it.isSupported && onDeviceClients[it]?.isSupported == true }
            .forEach { setStatus(it, NetworkStatus.Ready(emptyList())) }
    }

    private suspend fun loadStatus(network: ChatNetwork) {
        if (!bridgeRepository.observeIsConfigured(network).first()) {
            setStatus(network, NetworkStatus.NotConfigured)
            return
        }
        val status = when (val result = bridgeRepository.getStatus(network)) {
            is Result.Success -> NetworkStatus.Ready(result.data.logins)
            is Result.Failure -> NetworkStatus.Unavailable(result.error.toUiText())
        }
        setStatus(network, status)
    }

    private fun disconnect(network: ChatNetwork, loginId: String) {
        viewModelScope.launch {
            bridgeRepository.logout(network, loginId)
                .onSuccess { loadStatus(network) }
                .onFailure { eventChannel.send(NetworksEvent.Error(it.toUiText())) }
        }
    }

    private fun setStatus(network: ChatNetwork, status: NetworkStatus) {
        _state.update { state ->
            state.copy(items = state.items.map { if (it.network == network) it.copy(status = status) else it })
        }
    }
}
