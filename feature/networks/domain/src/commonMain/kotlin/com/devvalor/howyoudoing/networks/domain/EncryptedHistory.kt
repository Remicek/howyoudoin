package com.devvalor.howyoudoing.networks.domain

import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Error

/**
 * Messenger keeps end-to-end encrypted chat history in a server-side backup unlocked with the user's 6-digit PIN.
 * Without it, a newly linked bridge only sees new messages in those chats.
 */
data class EncryptedHistoryStatus(
    val isRestored: Boolean,
    val isBackupAvailable: Boolean
) {
    val canRestore: Boolean get() = isBackupAvailable && !isRestored
}

sealed interface HistoryRestoreError : Error {
    data class WrongPin(val attemptsRemaining: Int) : HistoryRestoreError
    /** Messenger locked the backup after too many wrong PINs. */
    data object NoAttemptsLeft : HistoryRestoreError
    data object NoBackup : HistoryRestoreError
    /** The bridge's Messenger session expired; reconnecting Messenger fixes it. */
    data object SessionExpired : HistoryRestoreError
    data class Remote(val error: DataError.Remote) : HistoryRestoreError
}
