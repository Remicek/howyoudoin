package com.devvalor.howyoudoing.app.premium

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Suppress("MagicNumber")
class PeriodDaysTest {
    @Test
    fun playsTrialPeriodsReadAsDays() {
        assertEquals(7, periodDays("P7D"))
        assertEquals(7, periodDays("P1W"))
        assertEquals(14, periodDays("P2W"))
        assertEquals(30, periodDays("P1M"))
        assertNull(periodDays("P0D"))
        assertNull(periodDays("seven days"))
    }
}
