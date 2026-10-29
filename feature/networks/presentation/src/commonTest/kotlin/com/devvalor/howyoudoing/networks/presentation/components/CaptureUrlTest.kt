package com.devvalor.howyoudoing.networks.presentation.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureUrlTest {

    private val linkedInFeed = "^https://www\\.linkedin\\.com/feed"

    @Test
    fun withoutAPatternAnyPageWillDo() {
        assertTrue(isAtCaptureUrl(null, "https://www.linkedin.com/login"))
        assertTrue(isAtCaptureUrl(null, null))
    }

    @Test
    fun linkedInWaitsForTheFeed() {
        assertFalse(isAtCaptureUrl(linkedInFeed, "https://www.linkedin.com/login"))
        assertFalse(isAtCaptureUrl(linkedInFeed, "https://www.linkedin.com/checkpoint/challenge/abc"))
        assertFalse(isAtCaptureUrl(linkedInFeed, null))
        assertTrue(isAtCaptureUrl(linkedInFeed, "https://www.linkedin.com/feed/?trk=login"))
    }
}
