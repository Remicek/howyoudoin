package com.devvalor.howyoudoing.core.domain.app

import kotlinx.coroutines.flow.Flow

/** Inbox offers the user can turn off for good ("Don't show again"). */
enum class Tip(val key: String) {
    /** Verify this device with the Matrix recovery key. */
    MATRIX_KEYS("matrix_keys"),

    /** Restore Messenger's end-to-end encrypted history with the Messenger PIN. */
    ENCRYPTED_HISTORY("encrypted_history"),
}

/** Which [Tip]s were turned off; Settings → Hidden tips brings them all back. */
interface HiddenTips {
    fun observeHidden(tip: Tip): Flow<Boolean>

    /** Whether any tip is turned off (Settings then offers bringing them back). */
    fun observeAnyHidden(): Flow<Boolean>

    suspend fun hide(tip: Tip)

    suspend fun showAll()
}
