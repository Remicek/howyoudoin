package com.relay.auth.presentation.login

import com.relay.core.presentation.util.UiText

data class LoginState(
    val homeserver: String = "",
    val username: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val isLoggingIn: Boolean = false,
    val error: UiText? = null
) {
    val canLogin: Boolean
        get() = username.isNotBlank() && password.isNotBlank() &&
            (homeserver.isNotBlank() || username.contains(":"))
}
