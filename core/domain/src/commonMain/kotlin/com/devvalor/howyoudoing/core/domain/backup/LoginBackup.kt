package com.devvalor.howyoudoing.core.domain.backup

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Logins kept in the platform's encrypted backup (Android: Google backup, end-to-end encrypted with the screen
 * lock), so a new phone or a reinstall can reconnect without logging in again. Nothing goes to a server of ours.
 * WhatsApp and Signal are never backed up (their device keys shouldn't be copied); the backup only remembers they
 * were connected, so the user is told to link them again.
 */
interface LoginBackup {
    val isSupported: Boolean

    fun observeEnabled(): Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)

    /** Saved logins found after a restore that aren't connected here yet; null when there's nothing to offer. */
    suspend fun restoreOffer(): LoginRestoreOffer?

    /** Reconnects the chosen networks (and the app's own session, if it was saved); returns the ones that failed. */
    suspend fun restore(networks: Set<ChatNetwork>): List<ChatNetwork>

    /** The user doesn't want the saved logins: don't offer them again. */
    suspend fun discard()

    /** True once (after two networks got connected) until the user answers [answerBackupOffer]. */
    fun observeShouldOfferBackup(): Flow<Boolean>

    /** The answer to the backup offer: turns backup on or off; the offer isn't shown again either way. */
    suspend fun answerBackupOffer(enable: Boolean)

    /**
     * When the platform last backed the app up (proof that its backup is set up and working), or null if it hasn't
     * yet. Apps can't ask whether Google backup is on, so this is the best sign.
     */
    suspend fun lastBackupMillis(): Long?

    /** Opens the phone's backup settings (Google backup), so the user can turn it on. */
    fun openSystemBackupSettings()
}

data class LoginRestoreOffer(
    val savedAtMillis: Long,
    /** Networks whose login can be restored. */
    val restorable: List<ChatNetwork>,
    /** The app's own session (server or this-phone-only mode) was saved too and nothing is set up here yet. */
    val includesAppSession: Boolean,
    /** Connected before, but have to be linked again (WhatsApp, Signal). */
    val toRelink: List<ChatNetwork>
)

/** Where there's no platform backup. */
object NoLoginBackup : LoginBackup {
    override val isSupported = false
    override fun observeEnabled(): Flow<Boolean> = flowOf(false)
    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun restoreOffer(): LoginRestoreOffer? = null
    override suspend fun restore(networks: Set<ChatNetwork>): List<ChatNetwork> = networks.toList()
    override suspend fun discard() = Unit
    override fun observeShouldOfferBackup(): Flow<Boolean> = flowOf(false)
    override suspend fun answerBackupOffer(enable: Boolean) = Unit
    override suspend fun lastBackupMillis(): Long? = null
    override fun openSystemBackupSettings() = Unit
}
