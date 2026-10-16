package com.devvalor.howyoudoing.auth.presentation.login

import com.devvalor.howyoudoing.core.domain.config.BackendConfig
import com.devvalor.howyoudoing.core.presentation.util.UiText

data class LoginState(
    val homeserver: String = BackendConfig.DEFAULT_HOMESERVER,
    val username: String = BackendConfig.DEFAULT_USERNAME,
    val password: String = BackendConfig.DEFAULT_PASSWORD,
    val isPasswordVisible: Boolean = false,
    val isLoggingIn: Boolean = false,
    val error: UiText? = null
) {
    val canLogin: Boolean
        get() = username.isNotBlank() && password.isNotBlank() &&
            (homeserver.isNotBlank() || username.contains(":"))
}
