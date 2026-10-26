package com.devvalor.howyoudoing.networks.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.networks.domain.OnboardingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreOnboardingRepository(
    private val dataStore: DataStore<Preferences>
) : OnboardingRepository {

    private val key = stringSetPreferencesKey("KEY_DISMISSED_TUTORIALS")
    private val riskAcceptedKey = booleanPreferencesKey("KEY_RISK_ACCEPTED")

    override fun observeDismissedTutorials(): Flow<Set<ChatNetwork>> =
        dataStore.data.map { prefs ->
            prefs[key].orEmpty().mapNotNull { name -> ChatNetwork.entries.firstOrNull { it.name == name } }.toSet()
        }

    override suspend fun setTutorialDismissed(network: ChatNetwork, dismissed: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[key].orEmpty()
            prefs[key] = if (dismissed) current + network.name else current - network.name
        }
    }

    override fun observeRiskAccepted(): Flow<Boolean> = dataStore.data.map { it[riskAcceptedKey] ?: false }

    override suspend fun setRiskAccepted() {
        dataStore.edit { it[riskAcceptedKey] = true }
    }
}
