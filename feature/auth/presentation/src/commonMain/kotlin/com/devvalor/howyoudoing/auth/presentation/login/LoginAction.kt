package com.devvalor.howyoudoing.auth.presentation.login

sealed interface LoginAction {
    data class OnHomeserverChange(val homeserver: String) : LoginAction
    data class OnUsernameChange(val username: String) : LoginAction
    data class OnPasswordChange(val password: String) : LoginAction
    data object OnTogglePasswordVisibility : LoginAction
    data object OnLoginClick : LoginAction
    data object OnDemoClick : LoginAction
    /** Start without a server: chats connect from the phone itself. */
    data object OnLocalClick : LoginAction
}
