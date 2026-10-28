package com.devvalor.howyoudoing.auth.presentation.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.auth.SessionStorage
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceAccounts
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val sessionStorage: SessionStorage,
    onDevice: OnDeviceAccounts
) : ViewModel() {

    private val eventChannel = Channel<LoginEvent>()
    val events = eventChannel.receiveAsFlow()

    private val _state = MutableStateFlow(
        LoginState(
            networks = onDevice.all
                .filter { it.isSupported && it.network.isBridged }
                .map { it.network }
                .sortedBy { it.ordinal }
        )
    )
    val state = _state.asStateFlow()

    fun onAction(action: LoginAction) {
        when (action) {
            LoginAction.OnDemoClick -> start(AuthInfo.demo())
            LoginAction.OnLocalClick -> start(AuthInfo.localOnly())
        }
    }

    private fun start(session: AuthInfo) {
        viewModelScope.launch {
            sessionStorage.set(session)
            eventChannel.send(LoginEvent.Success)
        }
    }
}
