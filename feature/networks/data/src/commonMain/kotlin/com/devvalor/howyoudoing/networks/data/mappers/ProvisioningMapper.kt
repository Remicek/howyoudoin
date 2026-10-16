package com.devvalor.howyoudoing.networks.data.mappers

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.networks.data.dto.CookieFieldDto
import com.devvalor.howyoudoing.networks.data.dto.InputFieldDto
import com.devvalor.howyoudoing.networks.data.dto.LoginFlowDto
import com.devvalor.howyoudoing.networks.data.dto.LoginStepDto
import com.devvalor.howyoudoing.networks.data.dto.WhoamiLoginDto
import com.devvalor.howyoudoing.networks.data.dto.WhoamiResponseDto
import com.devvalor.howyoudoing.networks.domain.BridgeLoginFlow
import com.devvalor.howyoudoing.networks.domain.BridgeLoginStep
import com.devvalor.howyoudoing.networks.domain.BridgeStatus
import com.devvalor.howyoudoing.networks.domain.DisplayType
import com.devvalor.howyoudoing.networks.domain.InputFieldType
import com.devvalor.howyoudoing.networks.domain.LoginInputField
import com.devvalor.howyoudoing.networks.domain.NetworkLogin
import com.devvalor.howyoudoing.networks.domain.RequiredCookie

fun WhoamiResponseDto.toBridgeStatus(network: ChatNetwork) = BridgeStatus(
    network = network,
    flows = loginFlows.map { it.toDomain() },
    logins = logins.map { it.toDomain() }
)

fun LoginFlowDto.toDomain() = BridgeLoginFlow(id = id, name = name.ifBlank { id }, description = description)

fun WhoamiLoginDto.toDomain() = NetworkLogin(id = id, name = name ?: id, state = state?.stateEvent)

/** @return null for step types this client doesn't know. */
fun LoginStepDto.toDomain(): BridgeLoginStep? = when (type) {
    "display_and_wait" -> BridgeLoginStep.DisplayAndWait(
        loginId = loginId,
        stepId = stepId,
        instructions = instructions,
        displayType = when (displayAndWait?.type) {
            "qr" -> DisplayType.QR
            "code" -> DisplayType.CODE
            "emoji" -> DisplayType.EMOJI
            else -> DisplayType.NOTHING
        },
        data = displayAndWait?.data
    )
    "user_input" -> BridgeLoginStep.UserInput(
        loginId = loginId,
        stepId = stepId,
        instructions = instructions,
        fields = userInput?.fields.orEmpty().map { it.toDomain() }
    )
    "cookies" -> cookies?.let { cookies ->
        BridgeLoginStep.Cookies(
            loginId = loginId,
            stepId = stepId,
            instructions = instructions,
            url = cookies.url,
            userAgent = cookies.userAgent,
            cookies = cookies.fields.mapNotNull { it.toRequiredCookie() }
        )
    }
    "complete" -> BridgeLoginStep.Complete(
        loginId = loginId,
        stepId = stepId,
        instructions = instructions,
        userLoginId = complete?.userLoginId
    )
    else -> null
}

private fun InputFieldDto.toDomain() = LoginInputField(
    id = id,
    name = name.ifBlank { id },
    description = description,
    type = when (type) {
        "username" -> InputFieldType.USERNAME
        "password" -> InputFieldType.PASSWORD
        "phone_number" -> InputFieldType.PHONE_NUMBER
        "email" -> InputFieldType.EMAIL
        "2fa_code" -> InputFieldType.TWO_FACTOR_CODE
        "token" -> InputFieldType.TOKEN
        "url" -> InputFieldType.URL
        "domain" -> InputFieldType.DOMAIN
        else -> InputFieldType.OTHER
    },
    pattern = pattern,
    defaultValue = defaultValue,
    isOptional = optional,
    minLength = minLength,
    maxLength = maxLength
)

/**
 * Only plain cookies can be read from a web view.
 * TODO: support local_storage / request header / special sources some bridges use.
 */
private fun CookieFieldDto.toRequiredCookie(): RequiredCookie? {
    val cookieName = sources?.firstOrNull { it.type == "cookie" }?.name
        ?: name.takeIf { type == "cookie" }
        ?: return null
    return RequiredCookie(fieldId = id, cookieName = cookieName, isRequired = required)
}
