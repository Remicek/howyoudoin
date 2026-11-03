package com.devvalor.howyoudoing.core.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataStoreSessionStorageTest {

    private class MemoryDataStore : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    /** Marks ciphertext with a prefix; anything without it "can't be decrypted", like a legacy plaintext value. */
    private object PrefixCipher : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:").takeIf { encoded.startsWith("enc:") }
    }

    /** What an older build did: store the JSON as is. */
    private object NoCipher : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(encoded: String): String? = null
    }

    private val key = stringPreferencesKey("KEY_AUTH_INFO")
    private val dataStore = MemoryDataStore()
    private val info = AuthInfo("https://matrix.example", "@me:example", "secret-token", "DEVICE")

    @Test
    fun aSessionIsStoredEncryptedAndReadBack() = runTest {
        val storage = DataStoreSessionStorage(dataStore, PrefixCipher)
        storage.set(info)
        assertTrue(dataStore.state.value[key]!!.startsWith("enc:"))
        assertEquals(info, storage.get())
    }

    @Test
    fun aPlaintextSessionFromAnOlderBuildIsReadAndReEncrypted() = runTest {
        DataStoreSessionStorage(dataStore, NoCipher).set(info)
        assertTrue(dataStore.state.value[key]!!.contains("secret-token"))

        assertEquals(info, DataStoreSessionStorage(dataStore, PrefixCipher).get())
        assertTrue(dataStore.state.value[key]!!.startsWith("enc:"))
    }

    @Test
    fun anUnreadableValueIsNoSession() = runTest {
        dataStore.updateData { emptyPreferences().toMutablePreferences().apply { this[key] = "garbage" } }
        assertNull(DataStoreSessionStorage(dataStore, PrefixCipher).get())
    }
}
