package com.devvalor.howyoudoing.networks.presentation.components.phone

/** Turns "country + what the user typed" into an E.164 number, e.g. PL + "600 123 456" → +48600123456. */
object PhoneNumbers {

    // Italy (and the countries sharing its numbering plan) keep the leading 0 internationally
    private val KEEPS_LEADING_ZERO = setOf("IT", "SM", "VA")

    fun nationalDigits(country: Country, input: String): String {
        val digits = input.filter(Char::isDigit)
        // Domestic trunk prefix: UK "07986…" is dialled internationally as +44 7986…
        return if (country.isoCode in KEEPS_LEADING_ZERO) digits else digits.trimStart('0')
    }

    fun toE164(country: Country, input: String): String = "+" + country.dialCode + nationalDigits(country, input)

    /** E.164 allows at most 15 digits; anything under 8 in total can't be a mobile number. */
    fun isPlausible(country: Country, input: String, pattern: String?): Boolean {
        val national = nationalDigits(country, input)
        val e164 = toE164(country, input)
        val totalDigits = e164.length - 1
        if (national.length < 4 || totalDigits !in 8..15) return false
        return pattern.isNullOrBlank() || runCatching { Regex(pattern).matches(e164) }.getOrDefault(true)
    }

    /** Splits a pasted "+48 600…" / "0048 600…" into its country and the national part. */
    fun parseInternational(input: String): Pair<Country, String>? {
        val trimmed = input.trim()
        val digits = when {
            trimmed.startsWith("+") -> trimmed.filter(Char::isDigit)
            trimmed.startsWith("00") -> trimmed.filter(Char::isDigit).removePrefix("00")
            else -> return null
        }
        val country = Country.forDialledDigits(digits) ?: return null
        return country to digits.removePrefix(country.dialCode)
    }
}
