package com.devvalor.howyoudoing.auth.presentation.login

sealed interface LoginEvent {
    data object Success : LoginEvent
}
