package com.devvalor.howyoudoing.networks.presentation.components.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneNumbersTest {

    private fun country(iso: String) = Country.forIso(iso)!!

    @Test
    fun combinesCountryCodeAndNationalNumber() {
        assertEquals("+48600123456", PhoneNumbers.toE164(country("PL"), "600 123 456"))
    }

    @Test
    fun dropsDomesticTrunkZero() {
        assertEquals("+447986123456", PhoneNumbers.toE164(country("GB"), "07986 123456"))
    }

    @Test
    fun italyKeepsLeadingZero() {
        assertEquals("+390612345678", PhoneNumbers.toE164(country("IT"), "06 1234 5678"))
    }

    @Test
    fun parsesPastedInternationalNumbers() {
        assertEquals(country("GB") to "7986123456", PhoneNumbers.parseInternational("+44 7986 123456"))
        assertEquals(country("PL") to "600123456", PhoneNumbers.parseInternational("0048 600 123 456"))
        assertNull(PhoneNumbers.parseInternational("600 123 456"))
    }

    @Test
    fun longestDialCodeWinsAndSharedCodesPickTheMainCountry() {
        assertEquals("AG", Country.forDialledDigits("12684601234")?.isoCode)
        assertEquals("US", Country.forDialledDigits("12025550123")?.isoCode)
        assertEquals("RU", Country.forDialledDigits("79161234567")?.isoCode)
    }

    @Test
    fun rejectsIncompleteOrTooLongNumbers() {
        assertFalse(PhoneNumbers.isPlausible(country("PL"), "600", pattern = null))
        assertFalse(PhoneNumbers.isPlausible(country("PL"), "6001234567890123", pattern = null))
        assertTrue(PhoneNumbers.isPlausible(country("PL"), "600 123 456", pattern = """^\+\d+$"""))
    }

    @Test
    fun flagIsBuiltFromRegionalIndicators() {
        assertEquals("🇵🇱", country("PL").flag)
    }
}
