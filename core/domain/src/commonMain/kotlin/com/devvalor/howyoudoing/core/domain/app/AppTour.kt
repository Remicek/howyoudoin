package com.devvalor.howyoudoing.core.domain.app

import kotlinx.coroutines.flow.Flow

/** The short tour of the inbox shown once to a new user; Settings can show it again. */
interface AppTour {
    /** True until the tour has been finished or skipped. */
    fun observeShouldShow(): Flow<Boolean>

    /** Finished or skipped: don't show it again by itself. */
    suspend fun markSeen()

    /** Asked for from Settings: show it the next time the inbox opens. */
    suspend fun showAgain()
}
