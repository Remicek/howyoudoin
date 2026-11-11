package com.devvalor.howyoudoing.settings.presentation.main

import com.devvalor.howyoudoing.core.domain.app.RatePromptRecord
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RatePromptTest {

    private fun ask(
        networks: Int = 3,
        hidden: Boolean = false,
        record: RatePromptRecord = RatePromptRecord(),
        store: Boolean = true,
        now: Long = 1_000,
    ) = shouldAskForRating(networks, hidden, record, store, now)

    @Test
    fun asksOnceThreeNetworksAreConnected() {
        assertFalse(ask(networks = 2))
        assertTrue(ask(networks = 3))
        assertTrue(ask(networks = 5))
    }

    @Test
    fun dontShowAgainStopsIt() = assertFalse(ask(hidden = true))

    @Test
    fun afterRatingItNeverAsksAgain() = assertFalse(ask(record = RatePromptRecord(rated = true)))

    @Test
    fun notNowPutsItOffUntilLater() {
        val snoozed = RatePromptRecord(snoozedUntilMs = 5_000)
        assertFalse(ask(record = snoozed, now = 4_999))
        assertTrue(ask(record = snoozed, now = 5_000))
    }

    @Test
    fun withoutAStoreItNeverAsks() = assertFalse(ask(store = false))
}
