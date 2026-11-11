package com.devvalor.howyoudoing.core.domain.app

import kotlinx.coroutines.flow.Flow

/** Inbox offers the user can turn off for good ("Don't show again"). */
enum class Tip(val key: String) {
    /** Verify this device with the Matrix recovery key. */
    MATRIX_KEYS("matrix_keys"),

    /** Restore Messenger's end-to-end encrypted history with the Messenger PIN. */
    ENCRYPTED_HISTORY("encrypted_history"),

    /** Rate the app on Google Play, asked once a few networks are connected. */
    RATE_APP("rate_app"),
}

/** Which [Tip]s were turned off; Settings → Hidden tips brings them all back. */
interface HiddenTips {
    fun observeHidden(tip: Tip): Flow<Boolean>

    /** Whether any tip is turned off (Settings then offers bringing them back). */
    fun observeAnyHidden(): Flow<Boolean>

    suspend fun hide(tip: Tip)

    suspend fun showAll()
}
