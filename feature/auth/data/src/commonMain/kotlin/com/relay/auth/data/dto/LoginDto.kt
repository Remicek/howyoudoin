package com.relay.auth.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequestDto(
    val type: String = "m.login.password",
    val identifier: IdentifierDto,
    val password: String,
    @SerialName("initial_device_display_name")
    val initialDeviceDisplayName: String = "Relay"
)

@Serializable
data class IdentifierDto(
    val type: String = "m.id.user",
    val user: String
)

@Serializable
data class LoginResponseDto(
    @SerialName("user_id") val userId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("well_known") val wellKnown: WellKnownDto? = null
)

@Serializable
data class WellKnownDto(
    @SerialName("m.homeserver") val homeserver: BaseUrlDto? = null
)

@Serializable
data class BaseUrlDto(
    @SerialName("base_url") val baseUrl: String
)
