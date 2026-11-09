package com.devvalor.howyoudoing.settings.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.devvalor.howyoudoing.settings.domain.PinCheck
import com.devvalor.howyoudoing.settings.domain.PinHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class DataStoreAppLockRepositoryTest {

    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    /** Not a real hash, but salted and deterministic, which is all the repository relies on. */
    private class FakeHasher : PinHasher {
        private var next = 0
        override fun newSalt() = byteArrayOf((next++).toByte())
        val roundsUsed = mutableListOf<Int>()
        override fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray {
            roundsUsed += iterations
            return "$pin/${salt.joinToString()}/$iterations".encodeToByteArray()
        }
    }

    private val store = MemoryDataStore()
    private val hasher = FakeHasher()
    private val repository = DataStoreAppLockRepository(store, hasher)

    @Test
    fun the_lock_is_off_until_a_pin_is_set() = runTest {
        assertFalse(repository.observeSettings().first().isEnabled)
        repository.setPin("1234")
        assertTrue(repository.observeSettings().first().isEnabled)
    }

    @Test
    fun the_right_pin_unlocks_and_a_wrong_one_does_not() = runTest {
        repository.setPin("1234")
        assertEquals(PinCheck.Correct, repository.checkPin("1234"))
        assertIs<PinCheck.Wrong>(repository.checkPin("0000"))
    }

    @Test
    fun five_wrong_pins_make_the_next_attempt_wait_even_with_the_right_pin() = runTest {
        repository.setPin("1234")
        repeat(4) { assertIs<PinCheck.Wrong>(repository.checkPin("0000")) }
        val fifth = repository.checkPin("0000")
        assertIs<PinCheck.TooManyAttempts>(fifth)
        assertEquals(30, fifth.seconds)
        assertIs<PinCheck.TooManyAttempts>(repository.checkPin("1234"))
    }

    @Test
    fun the_right_pin_resets_the_count_of_wrong_ones() = runTest {
        repository.setPin("1234")
        repeat(4) { repository.checkPin("0000") }
        assertEquals(PinCheck.Correct, repository.checkPin("1234"))
        assertIs<PinCheck.Wrong>(repository.checkPin("0000"))
    }

    @Test
    fun turning_the_lock_off_forgets_the_pin_and_fingerprint() = runTest {
        repository.setPin("1234")
        repository.setBiometricEnabled(true)
        repository.disable()
        val settings = repository.observeSettings().first()
        assertFalse(settings.isEnabled)
        assertFalse(settings.isBiometricEnabled)
    }

    @Test
    fun the_question_after_signing_in_is_asked_once() = runTest {
        assertFalse(repository.observeLockOffered().first())
        repository.setLockOffered()
        assertTrue(repository.observeLockOffered().first())
    }

    @Test
    fun choosing_or_removing_a_pin_counts_as_answering_the_question() = runTest {
        repository.setPin("1234")
        assertTrue(repository.observeLockOffered().first())

        val other = DataStoreAppLockRepository(MemoryDataStore(), FakeHasher())
        other.disable()
        assertTrue(other.observeLockOffered().first())
    }

    @Test
    fun a_new_pin_is_hashed_with_the_current_rounds() = runTest {
        repository.setPin("1234")
        assertEquals(listOf(PinHasher.ITERATIONS), hasher.roundsUsed)
    }

    @Test
    fun a_pin_hashed_before_the_rounds_were_stored_still_unlocks_and_is_hashed_again() = runTest {
        // What older versions stored: a salt and a 120k-round hash, without the rounds
        val salt = byteArrayOf(42)
        store.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                this[stringPreferencesKey("app_lock_pin_salt")] = Base64.encode(salt)
                this[stringPreferencesKey("app_lock_pin_hash")] =
                    Base64.encode(hasher.hash("1234", salt, PinHasher.LEGACY_ITERATIONS))
            }
        }
        hasher.roundsUsed.clear()

        assertIs<PinCheck.Wrong>(repository.checkPin("0000"))
        assertEquals(PinCheck.Correct, repository.checkPin("1234"))
        assertEquals(PinHasher.ITERATIONS, store.data.first()[intPreferencesKey("app_lock_pin_iterations")])

        hasher.roundsUsed.clear()
        assertEquals(PinCheck.Correct, repository.checkPin("1234"))
        assertEquals(listOf(PinHasher.ITERATIONS), hasher.roundsUsed)
        assertIs<PinCheck.Wrong>(repository.checkPin("0000"))
    }
}
