package com.devvalor.howyoudoing.auth.presentation.login

import com.devvalor.howyoudoing.core.domain.config.BackendConfig
import com.devvalor.howyoudoing.core.presentation.util.UiText

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

    companion object {
        fun developmentDefaults() = LoginState(
            homeserver = BackendConfig.DEFAULT_HOMESERVER,
            username = BackendConfig.DEFAULT_USERNAME,
            password = BackendConfig.DEFAULT_PASSWORD
        )
    }
}
