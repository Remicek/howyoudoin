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
    ) : BridgeLoginStep {
        /** Messenger asks for its secure storage PIN after login to restore end-to-end encrypted history. */
        val isMessengerHistoryPin: Boolean get() = stepId == MESSENGER_HISTORY_PIN_STEP

        val canSkip: Boolean get() = fields.isNotEmpty() && fields.all { it.isOptional }

        companion object {
            const val MESSENGER_HISTORY_PIN_STEP = "fi.mau.meta.history_pin"
        }
    }

    /** Log in on the network's website inside a web view, then hand the session cookies to the bridge. */
    data class Cookies(
        override val loginId: String,
        override val stepId: String,
        override val instructions: String,
        val url: String,
        val userAgent: String?,
        val cookies: List<RequiredCookie>,
        /** Runs in the page; returns a JSON object of more values (e.g. a token in the page's storage). */
        val extractScript: String? = null,
        /** The values are taken only once the page's address matches this, e.g. after "confirm it's you" steps. */
        val waitForUrl: String? = null,
        /** Hands over every cookie of the site (and every extracted value), not only [cookies]. */
        val keepAllCookies: Boolean = false
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
    val defaultValue: String? = null,
    /** Can be left empty; bridges use this for "skip" (e.g. Messenger's history PIN). */
    val isOptional: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null
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
