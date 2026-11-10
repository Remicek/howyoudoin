package com.devvalor.howyoudoing.core.data.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.devvalor.howyoudoing.core.domain.app.AppTour
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreAppTour(private val dataStore: DataStore<Preferences>) : AppTour {

    override fun observeShouldShow(): Flow<Boolean> = dataStore.data.map { it[TOUR_SEEN] != true }

    override suspend fun markSeen() {
        dataStore.edit { it[TOUR_SEEN] = true }
    }

    override suspend fun showAgain() {
        dataStore.edit { it[TOUR_SEEN] = false }
    }

    private companion object {
        val TOUR_SEEN = booleanPreferencesKey("inbox_tour_seen")
    }
}
