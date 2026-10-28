package com.devvalor.howyoudoing.auth.presentation.login

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

data class LoginState(
    /** The networks this build can connect from the phone, shown as icons under the tagline. */
    val networks: List<ChatNetwork> = emptyList()
)
