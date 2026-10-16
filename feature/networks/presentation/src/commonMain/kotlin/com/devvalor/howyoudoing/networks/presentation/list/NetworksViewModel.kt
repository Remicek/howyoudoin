package com.devvalor.howyoudoing.networks.presentation.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.domain.util.onFailure
import com.devvalor.howyoudoing.core.domain.util.onSuccess
import com.devvalor.howyoudoing.core.presentation.util.toUiText
import com.devvalor.howyoudoing.networks.domain.BridgeRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NetworksViewModel(
    private val bridgeRepository: BridgeRepository
) : ViewModel() {

    private val eventChannel = Channel<NetworksEvent>()
    val events = eventChannel.receiveAsFlow()

    private val _state = MutableStateFlow(NetworksState())
    val state = _state.asStateFlow()

    fun onAction(action: NetworksAction) {
        when (action) {
            NetworksAction.OnRefresh -> refresh()
            is NetworksAction.OnDisconnectClick -> disconnect(action.network, action.loginId)
            else -> Unit
        }
    }

    private fun refresh() {
        ChatNetwork.bridged.filter { it.isSupported }.forEach { network ->
            viewModelScope.launch { loadStatus(network) }
        }
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
