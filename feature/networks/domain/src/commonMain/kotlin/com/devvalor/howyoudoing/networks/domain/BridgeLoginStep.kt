package com.devvalor.howyoudoing.networks.domain

/**
 * One step of a mautrix bridgev2 login process. Bridges drive the flow: each submit
 * returns the next step until [Complete]. The same model covers every network, so new
 * bridges mostly need no extra UI.
 */
sealed interface BridgeLoginStep {
    val loginId: String
    val stepId: String
    val instructions: String

    /** Show something (QR code, pairing code, emoji) and wait for the user to act on their phone. */
    data class DisplayAndWait(
        override val loginId: String,
        override val stepId: String,
        override val instructions: String,
        val displayType: DisplayType,
        val data: String?
    ) : BridgeLoginStep

    data class UserInput(
        override val loginId: String,
        override val stepId: String,
        override val instructions: String,
        val fields: List<LoginInputField>
    ) : BridgeLoginStep

    /** Log in on the network's website inside a web view, then hand the session cookies to the bridge. */
    data class Cookies(
        override val loginId: String,
        override val stepId: String,
        override val instructions: String,
        val url: String,
        val userAgent: String?,
        val cookies: List<RequiredCookie>
    ) : BridgeLoginStep

    data class Complete(
        override val loginId: String,
        override val stepId: String,
        override val instructions: String,
        val userLoginId: String?
    ) : BridgeLoginStep
}

enum class DisplayType {
    QR,
    CODE,
    EMOJI,
    NOTHING
}

data class LoginInputField(
    val id: String,
    val name: String,
    val description: String,
    val type: InputFieldType,
    val pattern: String? = null,
    val defaultValue: String? = null
)

enum class InputFieldType {
    USERNAME,
    PASSWORD,
    PHONE_NUMBER,
    EMAIL,
    TWO_FACTOR_CODE,
    TOKEN,
    URL,
    DOMAIN,
    OTHER
}

/**
 * @param fieldId key the bridge expects in the submitted map
 * @param cookieName name of the browser cookie holding the value
 */
data class RequiredCookie(
    val fieldId: String,
    val cookieName: String,
    val isRequired: Boolean
)
