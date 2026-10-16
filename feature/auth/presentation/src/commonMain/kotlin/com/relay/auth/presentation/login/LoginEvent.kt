package com.relay.auth.presentation.login

sealed interface LoginEvent {
    data object Success : LoginEvent
}
