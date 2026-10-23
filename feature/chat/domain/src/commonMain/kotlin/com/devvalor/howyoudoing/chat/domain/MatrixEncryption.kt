package com.devvalor.howyoudoing.chat.domain

import kotlinx.coroutines.flow.Flow

/** End-to-end encryption of the Matrix account connected as a network. */
interface MatrixEncryption {
    val status: Flow<MatrixEncryptionStatus>

    /**
     * Verifies this device with the account's recovery key (Element calls it "Recovery key" or "Security Key") and
     * restores the room keys from the server's key backup; returns how many came back.
     */
    suspend fun verifyWithRecoveryKey(recoveryKey: String): Result<Int>
}

/** Whether encrypted rooms can be read. */
enum class MatrixEncryptionStatus {
    /** No Matrix account, or encryption isn't available on this platform. */
    UNAVAILABLE,
    /** New messages can be read; older ones and some people's apps need this device verified first. */
    UNVERIFIED,
    VERIFIED,
    /**
     * The login came back from a backup without this device's keys (they're never backed up), and the server
     * already has other keys for the device: encrypted chats need a new sign-in.
     */
    NEEDS_SIGN_IN
}
