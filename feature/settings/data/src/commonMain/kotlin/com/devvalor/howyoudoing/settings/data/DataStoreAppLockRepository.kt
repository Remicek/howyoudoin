@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.devvalor.howyoudoing.settings.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.devvalor.howyoudoing.settings.domain.AppLockRepository
import com.devvalor.howyoudoing.settings.domain.AppLockSettings
import com.devvalor.howyoudoing.settings.domain.LockTimeout
import com.devvalor.howyoudoing.settings.domain.PinCheck
import com.devvalor.howyoudoing.settings.domain.PinHasher
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The PIN is kept only as a salted, slow hash. After [FREE_ATTEMPTS] wrong PINs each further attempt waits
 * longer (30 s, doubling, at most 15 min), so a short PIN can't simply be tried through.
 */
@OptIn(ExperimentalEncodingApi::class)
class DataStoreAppLockRepository(
    private val dataStore: DataStore<Preferences>,
    private val hasher: PinHasher
) : AppLockRepository {

    override fun observeSettings(): Flow<AppLockSettings> = dataStore.data.map { prefs ->
        AppLockSettings(
            isEnabled = prefs[PIN_HASH] != null,
            isBiometricEnabled = prefs[BIOMETRIC] ?: false,
            lockAfter = prefs[LOCK_AFTER]?.let { name -> LockTimeout.entries.firstOrNull { it.name == name } }
                ?: LockTimeout.ONE_MINUTE,
            hidesNotificationContent = prefs[HIDE_NOTIFICATION_CONTENT] ?: true
        )
    }

    override suspend fun setPin(pin: String) {
        val salt = hasher.newSalt()
        val hash = hasher.hash(pin, salt, PinHasher.ITERATIONS)
        dataStore.edit { prefs ->
            prefs[PIN_SALT] = Base64.encode(salt)
            prefs[PIN_HASH] = Base64.encode(hash)
            prefs[PIN_ITERATIONS] = PinHasher.ITERATIONS
            prefs.remove(FAILED_ATTEMPTS)
            prefs.remove(LOCKED_OUT_UNTIL)
            // Choosing the lock answers the question asked after signing in
            prefs[LOCK_OFFERED] = true
        }
    }

    override suspend fun checkPin(pin: String): PinCheck {
        val prefs = dataStore.data.first()
        val now = Clock.System.now().toEpochMilliseconds()
        val lockedOutUntil = prefs[LOCKED_OUT_UNTIL] ?: 0L
        val salt = prefs[PIN_SALT]?.let { Base64.decode(it) }
        val expected = prefs[PIN_HASH]?.let { Base64.decode(it) }
        val iterations = prefs[PIN_ITERATIONS] ?: PinHasher.LEGACY_ITERATIONS
        return when {
            lockedOutUntil > now -> PinCheck.TooManyAttempts(secondsUntil(lockedOutUntil - now))
            salt == null || expected == null -> PinCheck.Correct
            constantTimeEquals(hasher.hash(pin, salt, iterations), expected) -> {
                if (iterations < PinHasher.ITERATIONS) {
                    // A hash from before the rounds were raised: the PIN is known now, so it's hashed again with more
                    setPin(pin)
                } else {
                    dataStore.edit { it.remove(FAILED_ATTEMPTS); it.remove(LOCKED_OUT_UNTIL) }
                }
                PinCheck.Correct
            }

            else -> recordWrongPin(prefs, now)
        }
    }

    private suspend fun recordWrongPin(prefs: Preferences, now: Long): PinCheck {
        val failures = (prefs[FAILED_ATTEMPTS] ?: 0) + 1
        val waitSeconds = if (failures < FREE_ATTEMPTS) {
            0L
        } else {
            val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(MAX_DOUBLINGS)
            (BASE_WAIT_SECONDS shl doublings).coerceAtMost(MAX_WAIT_SECONDS)
        }
        dataStore.edit { edited ->
            edited[FAILED_ATTEMPTS] = failures
            if (waitSeconds > 0) edited[LOCKED_OUT_UNTIL] = now + waitSeconds.seconds.inWholeMilliseconds
        }
        return if (waitSeconds > 0) PinCheck.TooManyAttempts(waitSeconds) else PinCheck.Wrong(FREE_ATTEMPTS - failures)
    }

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[BIOMETRIC] = enabled }
    }

    override suspend fun setLockAfter(timeout: LockTimeout) {
        dataStore.edit { it[LOCK_AFTER] = timeout.name }
    }

    override suspend fun setHidesNotificationContent(hides: Boolean) {
        dataStore.edit { it[HIDE_NOTIFICATION_CONTENT] = hides }
    }

    override suspend fun disable() {
        dataStore.edit { prefs ->
            listOf(PIN_HASH, PIN_SALT, PIN_ITERATIONS, BIOMETRIC, FAILED_ATTEMPTS, LOCKED_OUT_UNTIL).forEach {
                prefs.remove(it)
            }
            prefs[LOCK_OFFERED] = true
        }
    }

    override fun observeLockOffered(): Flow<Boolean> = dataStore.data.map { it[LOCK_OFFERED] ?: false }

    override suspend fun setLockOffered() {
        dataStore.edit { it[LOCK_OFFERED] = true }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private companion object {
        val PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
        val PIN_SALT = stringPreferencesKey("app_lock_pin_salt")

        /** The rounds [PIN_HASH] was made with; missing for hashes from before it was stored. */
        val PIN_ITERATIONS = intPreferencesKey("app_lock_pin_iterations")
        val BIOMETRIC = booleanPreferencesKey("app_lock_biometric")
        val LOCK_AFTER = stringPreferencesKey("app_lock_after")
        val HIDE_NOTIFICATION_CONTENT = booleanPreferencesKey("app_lock_hide_notification_content")
        val FAILED_ATTEMPTS = intPreferencesKey("app_lock_failed_attempts")
        val LOCKED_OUT_UNTIL = longPreferencesKey("app_lock_locked_out_until")
        val LOCK_OFFERED = booleanPreferencesKey("app_lock_offered")

        const val FREE_ATTEMPTS = 5
        const val BASE_WAIT_SECONDS = 30L
        const val MAX_WAIT_SECONDS = 15 * 60L

        /** The wait stops doubling after this many more wrong attempts (and is capped anyway). */
        const val MAX_DOUBLINGS = 10
    }
}

/** Whole seconds left, rounded up so the wait never shows 0 while it lasts. */
private fun secondsUntil(millis: Long): Long {
    val left = millis.milliseconds
    return left.inWholeSeconds + if (left.inWholeMilliseconds % 1.seconds.inWholeMilliseconds > 0) 1 else 0
}
