package com.relay.networks.data.mappers

import com.relay.core.domain.network.ChatNetwork
import com.relay.networks.data.dto.CookieFieldDto
import com.relay.networks.data.dto.InputFieldDto
import com.relay.networks.data.dto.LoginFlowDto
import com.relay.networks.data.dto.LoginStepDto
import com.relay.networks.data.dto.WhoamiLoginDto
import com.relay.networks.data.dto.WhoamiResponseDto
import com.relay.networks.domain.BridgeLoginFlow
import com.relay.networks.domain.BridgeLoginStep
import com.relay.networks.domain.BridgeStatus
import com.relay.networks.domain.DisplayType
import com.relay.networks.domain.InputFieldType
import com.relay.networks.domain.LoginInputField
import com.relay.networks.domain.NetworkLogin
import com.relay.networks.domain.RequiredCookie

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
    defaultValue = defaultValue
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
