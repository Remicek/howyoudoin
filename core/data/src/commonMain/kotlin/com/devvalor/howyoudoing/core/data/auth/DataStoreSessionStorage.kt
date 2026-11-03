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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * A session kept in DataStore under [keyName]; the app's own session uses the default key. The
 * serialized session (which holds the access token) is encrypted at rest with [cipher], a
 * device-bound key on Android. Values written by an older build were stored in the clear; they are
 * read transparently (see [decode]) and re-encrypted as soon as they're read, so an upgrade doesn't
 * leave the token in the clear until the next sign-in.
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
        // The DataStore is shared with other settings: only decrypt when this key's value actually changes, and do
        // it off the collector's thread (often Main), since a Keystore operation is a binder call.
        return dataStore.data
            .map { preferences -> preferences[authInfoKey] }
            .distinctUntilChanged()
            .map { stored -> stored?.let { decode(it) } }
            .flowOn(Dispatchers.Default)
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
    private suspend fun decode(stored: String): AuthInfo? {
        val decrypted = cipher.decrypt(stored)
        val info = runCatching {
            json.decodeFromString<AuthInfoSerializable>(decrypted ?: stored).toDomain()
        }.getOrNull()
        // A session from before encryption: re-save it encrypted, so the token doesn't stay in the clear.
        if (decrypted == null && info != null) {
            try {
                migrateLegacy(stored, info)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Still readable as before; the migration is retried on the next read.
            }
        }
        return info
    }

    private suspend fun migrateLegacy(legacy: String, info: AuthInfo) {
        val serialized = cipher.encrypt(json.encodeToString(info.toSerializable()))
        // Only replace the value we read; a sign-out or new sign-in in the meantime wins.
        dataStore.edit { prefs -> if (prefs[authInfoKey] == legacy) prefs[authInfoKey] = serialized }
    }
}
