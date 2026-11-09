package com.devvalor.howyoudoing.settings.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow

/** How the app is locked when it's opened or comes back from the background. */
data class AppLockSettings(
    /** A PIN is set; the app asks for it (or a fingerprint) before showing the inbox. */
    val isEnabled: Boolean = false,
    /** A fingerprint or face can unlock instead of the PIN. */
    val isBiometricEnabled: Boolean = false,
    val lockAfter: LockTimeout = LockTimeout.ONE_MINUTE,
    /** Notifications say "New message" instead of who wrote what. */
    val hidesNotificationContent: Boolean = true
)

enum class LockTimeout(val duration: Duration) {
    IMMEDIATELY(Duration.ZERO),
    ONE_MINUTE(1.minutes),
    FIVE_MINUTES(5.minutes),
    FIFTEEN_MINUTES(15.minutes)
}

sealed interface PinCheck {
    data object Correct : PinCheck
    data class Wrong(val attemptsBeforeWait: Int) : PinCheck
    /** Too many wrong attempts; try again after [seconds]. */
    data class TooManyAttempts(val seconds: Long) : PinCheck
}

interface AppLockRepository {
    fun observeSettings(): Flow<AppLockSettings>

    /** Turns the lock on with this PIN, or changes the PIN. */
    suspend fun setPin(pin: String)

    suspend fun checkPin(pin: String): PinCheck

    suspend fun setBiometricEnabled(enabled: Boolean)

    suspend fun setLockAfter(timeout: LockTimeout)

    suspend fun setHidesNotificationContent(hides: Boolean)

    /** Turns the lock off and forgets the PIN. */
    suspend fun disable()

    /** Whether the user has been asked once, after signing in, if they want the lock. */
    fun observeLockOffered(): Flow<Boolean>

    suspend fun setLockOffered()
}

/** Derives a slow, salted hash of a PIN (platform crypto): PBKDF2-HMAC-SHA256 with [iterations] rounds. */
interface PinHasher {
    fun newSalt(): ByteArray
    fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray

    companion object {
        /** Rounds for new PIN hashes (OWASP's 2023 figure for PBKDF2-HMAC-SHA256). */
        const val ITERATIONS = 210_000

        /** Rounds of hashes stored before the count was stored with them; they're redone on the next unlock. */
        const val LEGACY_ITERATIONS = 120_000
    }
}

/** The phone's fingerprint / face unlock. */
interface BiometricAuthenticator {
    /** Hardware present and at least one fingerprint or face enrolled. */
    fun isAvailable(): Boolean

    /** Shows the system prompt; true when the user was recognised. */
    suspend fun authenticate(title: String, subtitle: String): Boolean
}
