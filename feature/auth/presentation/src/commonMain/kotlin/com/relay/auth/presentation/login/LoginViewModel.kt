package com.relay.auth.presentation.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.core.domain.auth.AuthInfo
import com.relay.core.domain.auth.AuthService
import com.relay.core.domain.auth.SessionStorage
import com.relay.core.domain.util.onFailure
import com.relay.core.domain.util.onSuccess
import com.relay.core.presentation.util.toUiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authService: AuthService,
    private val sessionStorage: SessionStorage
) : ViewModel() {

    private val eventChannel = Channel<LoginEvent>()
    val events = eventChannel.receiveAsFlow()

    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    fun onAction(action: LoginAction) {
        when (action) {
            is LoginAction.OnHomeserverChange -> {
                _state.update { it.copy(homeserver = action.homeserver, error = null) }
            }
            is LoginAction.OnUsernameChange -> {
                _state.update { it.copy(username = action.username, error = null) }
            }
            is LoginAction.OnPasswordChange -> {
                _state.update { it.copy(password = action.password, error = null) }
            }
            LoginAction.OnTogglePasswordVisibility -> {
                _state.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
            }
            LoginAction.OnLoginClick -> login()
            LoginAction.OnDemoClick -> startDemo()
        }
    }

    private fun login() {
        val current = state.value
        if (!current.canLogin || current.isLoggingIn) return

        viewModelScope.launch {
            _state.update { it.copy(isLoggingIn = true, error = null) }

            authService
                .login(
                    homeserver = current.homeserver,
                    username = current.username,
                    password = current.password
                )
                .onSuccess {
                    _state.update { it.copy(isLoggingIn = false) }
                    eventChannel.send(LoginEvent.Success)
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            error = error.toUiText(),
                            isLoggingIn = false
                        )
                    }
                }
        }
    }

    private fun startDemo() {
        viewModelScope.launch {
            sessionStorage.set(AuthInfo.demo())
            eventChannel.send(LoginEvent.Success)
        }
    }
}
