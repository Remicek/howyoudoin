package com.devvalor.howyoudoing.networks.presentation.connect

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.presentation.util.UiText
import com.devvalor.howyoudoing.networks.domain.BridgeLoginFlow
import com.devvalor.howyoudoing.networks.domain.BridgeLoginStep
import com.devvalor.howyoudoing.networks.domain.InputFieldType
import com.devvalor.howyoudoing.networks.domain.LoginInputField
import com.devvalor.howyoudoing.networks.presentation.components.phone.Country
import com.devvalor.howyoudoing.networks.presentation.components.phone.PhoneNumbers

data class ConnectNetworkState(
    val network: ChatNetwork,
    val phase: ConnectPhase = ConnectPhase.Loading,
    val bridgeUrl: String = "",
    val inputValues: Map<String, String> = emptyMap(),
    /** Selected country (ISO code) per phone number field; the input then holds the national number. */
    val phoneCountries: Map<String, String> = emptyMap(),
    val isSubmitting: Boolean = false,
    val error: UiText? = null,
    /** Developer-only bridge URL override (debug builds). */
    val canEditBridgeUrl: Boolean = false
) {
    val canSubmitInput: Boolean
        get() {
            val step = (phase as? ConnectPhase.Step)?.step as? BridgeLoginStep.UserInput ?: return false
            return !isSubmitting && step.fields.all(::isValid)
        }

    fun countryFor(fieldId: String): Country = Country.forIso(phoneCountries[fieldId]) ?: Country.forIso("US")!!

    /** What gets sent to the bridge: phone fields become full international numbers. */
    fun submittedValue(field: LoginInputField): String {
        val raw = inputValues[field.id].orEmpty().trim()
        return if (field.type == InputFieldType.PHONE_NUMBER) PhoneNumbers.toE164(countryFor(field.id), raw) else raw
    }

    fun isValid(field: LoginInputField): Boolean {
        val raw = inputValues[field.id].orEmpty()
        return when (field.type) {
            InputFieldType.PHONE_NUMBER -> PhoneNumbers.isPlausible(countryFor(field.id), raw, field.pattern)
            else -> when {
                raw.isBlank() -> field.isOptional
                raw.length < (field.minLength ?: 0) -> false
                raw.length > (field.maxLength ?: Int.MAX_VALUE) -> false
                // Bridges send Go regexes; ignore ones Kotlin can't parse rather than block the user
                else -> field.pattern.let { pattern ->
                    pattern.isNullOrBlank() || runCatching { Regex(pattern).matches(raw) }.getOrDefault(true)
                }
            }
        }
    }
}

sealed interface ConnectPhase {
    data object Loading : ConnectPhase
    data object ConfigureBridge : ConnectPhase
    data class ChooseFlow(val flows: List<BridgeLoginFlow>) : ConnectPhase
    data class Step(val step: BridgeLoginStep) : ConnectPhase
    data class Connected(val accountName: String?) : ConnectPhase
}
