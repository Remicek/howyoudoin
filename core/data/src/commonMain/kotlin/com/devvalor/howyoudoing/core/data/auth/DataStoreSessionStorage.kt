package com.devvalor.howyoudoing.core.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.devvalor.howyoudoing.core.data.dto.AuthInfoSerializable
import com.devvalor.howyoudoing.core.data.mappers.toDomain
import com.devvalor.howyoudoing.core.data.mappers.toSerializable
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.auth.SessionStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * A session kept in DataStore under [keyName]; the app's own session uses the default key. The
 * serialized session (which holds the access token) is encrypted at rest with [cipher], a
 * device-bound key on Android. Values written by an older build were stored in the clear; they are
 * read transparently (see [decode]) and re-encrypted the next time the session is written.
 */
class DataStoreSessionStorage(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    keyName: String = "KEY_AUTH_INFO"
) : SessionStorage {

    private val authInfoKey = stringPreferencesKey(keyName)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override fun observeAuthInfo(): Flow<AuthInfo?> {
        return dataStore.data
            .map { preferences -> preferences[authInfoKey]?.let(::decode) }
            .distinctUntilChanged()
    }

    override suspend fun get(): AuthInfo? = observeAuthInfo().first()

    override suspend fun set(info: AuthInfo?) {
        if (info == null) {
            dataStore.edit { it.remove(authInfoKey) }
            return
        }

        val serialized = cipher.encrypt(json.encodeToString(info.toSerializable()))
        dataStore.edit { prefs ->
            prefs[authInfoKey] = serialized
        }
    }

    /** Decrypts the stored value, falling back to the raw string for sessions written before encryption. */
    private fun decode(stored: String): AuthInfo? {
        val plaintext = cipher.decrypt(stored) ?: stored
        return runCatching { json.decodeFromString<AuthInfoSerializable>(plaintext).toDomain() }.getOrNull()
    }
}
