package com.devvalor.howyoudoing.settings.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.devvalor.howyoudoing.settings.domain.PinCheck
import com.devvalor.howyoudoing.settings.domain.PinHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        override fun hash(pin: String, salt: ByteArray) = (pin + salt.joinToString()).encodeToByteArray()
    }

    private val repository = DataStoreAppLockRepository(MemoryDataStore(), FakeHasher())

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
}
