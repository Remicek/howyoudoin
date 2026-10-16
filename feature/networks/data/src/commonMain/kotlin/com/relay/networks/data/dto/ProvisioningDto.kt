package com.relay.networks.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of the mautrix bridgev2 provisioning API (/_matrix/provision/v3).

@Serializable
data class WhoamiResponseDto(
    @SerialName("login_flows") val loginFlows: List<LoginFlowDto> = emptyList(),
    val logins: List<WhoamiLoginDto> = emptyList()
)

@Serializable
data class LoginFlowsResponseDto(
    val flows: List<LoginFlowDto> = emptyList()
)

@Serializable
data class LoginFlowDto(
    val id: String,
    val name: String = "",
    val description: String = ""
)

@Serializable
data class WhoamiLoginDto(
    val id: String,
    val name: String? = null,
    val state: LoginStateDto? = null
)

@Serializable
data class LoginStateDto(
    @SerialName("state_event") val stateEvent: String? = null
)

@Serializable
data class LoginStepDto(
    @SerialName("login_id") val loginId: String,
    val type: String,
    @SerialName("step_id") val stepId: String,
    val instructions: String = "",
    @SerialName("display_and_wait") val displayAndWait: DisplayAndWaitDto? = null,
    @SerialName("user_input") val userInput: UserInputDto? = null,
    val cookies: CookiesDto? = null,
    val complete: CompleteDto? = null
)

@Serializable
data class DisplayAndWaitDto(
    val type: String,
    val data: String? = null
)

@Serializable
data class UserInputDto(
    val fields: List<InputFieldDto> = emptyList()
)

@Serializable
data class InputFieldDto(
    val type: String,
    val id: String,
    val name: String = "",
    val description: String = "",
    val pattern: String? = null,
    @SerialName("default_value") val defaultValue: String? = null
)

@Serializable
data class CookiesDto(
    val url: String,
    @SerialName("user_agent") val userAgent: String? = null,
    val fields: List<CookieFieldDto> = emptyList()
)

/**
 * Newer bridges describe where a value comes from in [sources]; older ones used a flat
 * `type`/`name` pair on the field itself. Both are accepted.
 */
@Serializable
data class CookieFieldDto(
    val id: String,
    val required: Boolean = true,
    val type: String? = null,
    val name: String? = null,
    val sources: List<CookieSourceDto>? = null
)

@Serializable
data class CookieSourceDto(
    val type: String,
    val name: String
)

@Serializable
data class CompleteDto(
    @SerialName("user_login_id") val userLoginId: String? = null
)
