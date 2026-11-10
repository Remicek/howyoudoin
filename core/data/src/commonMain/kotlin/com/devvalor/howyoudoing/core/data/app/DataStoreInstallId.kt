package com.devvalor.howyoudoing.core.data.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.devvalor.howyoudoing.core.domain.premium.InstallId
import kotlinx.coroutines.flow.first
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Made the first time it's needed (Uuid.random is cryptographically secure), then kept. */
class DataStoreInstallId(private val dataStore: DataStore<Preferences>) : InstallId {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun get(): String {
        dataStore.data.first()[INSTALL_ID]?.let { return it }
        var id = ""
        dataStore.edit { prefs ->
            id = prefs[INSTALL_ID] ?: Uuid.random().toHexString().also { prefs[INSTALL_ID] = it }
        }
        return id
    }

    private companion object {
        val INSTALL_ID = stringPreferencesKey("install_id")
    }
}
