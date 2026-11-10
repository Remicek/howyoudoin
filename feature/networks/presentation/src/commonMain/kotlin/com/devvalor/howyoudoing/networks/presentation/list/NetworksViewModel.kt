package com.devvalor.howyoudoing.networks.presentation.list

import com.devvalor.howyoudoing.networks.presentation.navigation.NetworksRoutes
import com.devvalor.howyoudoing.core.domain.ondevice.account
import androidx.navigation.toRoute
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.core.domain.ondevice.AccountLabels
import com.devvalor.howyoudoing.core.domain.ondevice.DuplicateAccounts
import com.devvalor.howyoudoing.core.presentation.util.UiText
import howyoudoin.feature.networks.presentation.generated.resources.Res
import howyoudoin.feature.networks.presentation.generated.resources.networks_duplicate_account
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceAccounts
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

@Suppress("LongParameterList") // Koin-injected collaborators
class NetworksViewModel(
    savedStateHandle: SavedStateHandle,
    private val bridgeRepository: BridgeRepository,
    private val onboardingRepository: OnboardingRepository,
    private val onDevice: OnDeviceAccounts,
    private val connectionMonitor: ConnectionMonitor,
    private val accountLabels: AccountLabels,
    duplicateAccounts: DuplicateAccounts
) : ViewModel() {

    private val onDeviceClients = onDevice.all.filter { it.slot == 0 }.associateBy { it.network }

    private val eventChannel = Channel<NetworksEvent>()
    val events = eventChannel.receiveAsFlow()

    private val _state = MutableStateFlow(
        NetworksState(
            troubleshoot = runCatching { savedStateHandle.toRoute<NetworksRoutes.List>().troubleshoot }.getOrNull()
                ?.let { name -> ChatNetwork.entries.firstOrNull { it.name == name } }
        )
    )
    val state = _state.asStateFlow()

    init {
        onboardingRepository.observeDismissedTutorials()
            .onEach { dismissed -> _state.update { it.copy(dismissedTutorials = dismissed) } }
            .launchIn(viewModelScope)
        onboardingRepository.observeRiskAccepted()
            .onEach { accepted -> _state.update { it.copy(isRiskAccepted = accepted) } }
            .launchIn(viewModelScope)
        onDeviceClients.values.forEach { client ->
            client.state
                .onEach { onDevice -> _state.update { it.copy(onDevice = it.onDevice + (client.network to onDevice)) } }
                .launchIn(viewModelScope)
        }
        onDevice.all.filter { it.isSupported }.forEach { client ->
            client.state
                .onEach { account -> onAccountState(client.account, account) }
                .launchIn(viewModelScope)
        }
        duplicateAccounts.rejected
            .onEach { duplicate ->
                _state.update { if (it.naming == duplicate.account) it.copy(naming = null) else it }
                val network = duplicate.account.network.displayName
                eventChannel.send(
                    NetworksEvent.Error(UiText.Resource(Res.string.networks_duplicate_account, arrayOf(network)))
                )
            }
            .launchIn(viewModelScope)
        accountLabels.observe()
            .onEach { labels -> _state.update { it.copy(labels = labels) } }
            .launchIn(viewModelScope)
    }

    fun onAction(action: NetworksAction) {
        when (action) {
            NetworksAction.OnRefresh -> refresh()
            NetworksAction.OnRiskAccepted -> viewModelScope.launch { onboardingRepository.setRiskAccepted() }
            is NetworksAction.OnDisconnectClick -> disconnect(action.network, action.loginId)
            is NetworksAction.OnDisconnectOnDeviceClick -> viewModelScope.launch {
                val account = AccountRef(action.network, action.slot)
                connectionMonitor.expectDisconnect(account)
                onDevice.forAccount(account)?.unlink()
                // Its name goes with it (one that's only logged out keeps it, for logging in again)
                accountLabels.remove(account)
            }

            is NetworksAction.OnReconnectAccountClick ->
                viewModelScope.launch { onDevice.forAccount(action.account)?.reconnect() }
            is NetworksAction.OnEditLabelClick -> _state.update { it.copy(naming = action.account) }
            NetworksAction.OnLabelDismiss -> _state.update { it.copy(naming = null) }
            is NetworksAction.OnLabelSave -> viewModelScope.launch {
                _state.update { it.copy(naming = null) }
                accountLabels.set(action.account, action.label.copy(name = action.label.name.trim()))
            }

            else -> Unit
        }
    }

    /**
     * Keeps [NetworksState.accounts] current. An account that just logged in next to another of its network, and has
     * no name yet, is offered one.
     */
    private fun onAccountState(account: AccountRef, state: OnDeviceMessengerState) {
        val before = _state.value.accounts[account]
        _state.update { it.copy(accounts = it.accounts + (account to state)) }
        val justLinked = before == OnDeviceMessengerState.NotLinked && state != OnDeviceMessengerState.NotLinked
        val current = _state.value
        if (justLinked && current.linkedAccounts(account.network).size > 1 && account !in current.labels) {
            _state.update { it.copy(naming = account) }
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
