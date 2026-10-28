package com.devvalor.howyoudoing.auth.presentation.login

sealed interface LoginAction {
    data object OnDemoClick : LoginAction
    /** Start without a server: chats connect from the phone itself. */
    data object OnLocalClick : LoginAction
}
