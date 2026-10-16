package com.relay.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.core.domain.auth.SessionStorage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val sessionStorage: SessionStorage
) : ViewModel() {

    private val eventChannel = Channel<MainEvent>()
    val events = eventChannel.receiveAsFlow()

    private val _state = MutableStateFlow(MainState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val isLoggedIn = sessionStorage.get() != null
            _state.update { it.copy(isCheckingAuth = false, isLoggedIn = isLoggedIn) }
            observeSession()
        }
    }

    /** Logout, or the homeserver rejecting our token, sends the user back to login. */
    private fun observeSession() {
        sessionStorage.observeAuthInfo()
            .map { it != null }
            .distinctUntilChanged()
            .drop(1)
            .onEach { isLoggedIn ->
                _state.update { it.copy(isLoggedIn = isLoggedIn) }
                if (!isLoggedIn) eventChannel.send(MainEvent.OnSessionEnded)
            }
            .launchIn(viewModelScope)
    }
}
