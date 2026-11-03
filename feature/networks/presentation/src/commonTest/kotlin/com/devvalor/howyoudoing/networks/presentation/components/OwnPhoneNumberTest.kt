package com.devvalor.howyoudoing.networks.presentation.components

import com.devvalor.howyoudoing.networks.presentation.components.phone.Country
import com.devvalor.howyoudoing.networks.presentation.components.phone.fillFromOwnNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnPhoneNumberTest {

    private var country: Country? = null
    private var number: String? = null

    private fun fill(ownNumber: String) = fillFromOwnNumber(ownNumber, { country = it }, { number = it })

    @Test
    fun anInternationalNumberAlsoPicksTheCountry() {
        assertTrue(fill("+48 600 123 456"))
        assertEquals("PL", country?.isoCode)
        assertEquals("600123456", number)
    }

    @Test
    fun aNationalNumberKeepsTheChosenCountry() {
        assertTrue(fill("600123456"))
        assertNull(country)
        assertEquals("600123456", number)
    }

    @Test
    fun noDigitsFillsNothing() {
        assertFalse(fill(""))
        assertNull(number)
    }
}
