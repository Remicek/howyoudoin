package com.devvalor.howyoudoing.core.domain.app

import kotlinx.coroutines.flow.Flow

/** What the "rate the app" prompt remembers ("Don't show again" is [Tip.RATE_APP] in [HiddenTips]). */
data class RatePromptRecord(
    /** "Not now" was tapped: not asked again before this time. */
    val snoozedUntilMs: Long = 0,
    /** The store page was opened from the prompt: never asked again. */
    val rated: Boolean = false,
)

interface RatePromptMemory {
    fun observe(): Flow<RatePromptRecord>

    suspend fun snooze(untilMs: Long)

    suspend fun markRated()
}
