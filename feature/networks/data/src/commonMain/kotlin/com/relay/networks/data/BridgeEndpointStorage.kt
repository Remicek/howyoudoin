package com.relay.networks.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.relay.core.domain.network.ChatNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Remembers where each bridge's provisioning API is reachable. */
class BridgeEndpointStorage(
    private val dataStore: DataStore<Preferences>
) {

    fun observe(network: ChatNetwork): Flow<String?> =
        dataStore.data.map { it[key(network)] }

    suspend fun get(network: ChatNetwork): String? = observe(network).first()

    suspend fun set(network: ChatNetwork, url: String) {
        dataStore.edit { it[key(network)] = url.trim().trimEnd('/') }
    }

    private fun key(network: ChatNetwork) = stringPreferencesKey("KEY_BRIDGE_URL_${network.name}")
}
