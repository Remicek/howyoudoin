package com.devvalor.howyoudoing.core.data.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.devvalor.howyoudoing.core.domain.app.RatePromptMemory
import com.devvalor.howyoudoing.core.domain.app.RatePromptRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreRatePromptMemory(private val dataStore: DataStore<Preferences>) : RatePromptMemory {

    override fun observe(): Flow<RatePromptRecord> = dataStore.data
        .map { RatePromptRecord(snoozedUntilMs = it[SNOOZED_UNTIL] ?: 0, rated = it[RATED] == true) }
        .distinctUntilChanged()

    override suspend fun snooze(untilMs: Long) {
        dataStore.edit { it[SNOOZED_UNTIL] = untilMs }
    }

    override suspend fun markRated() {
        dataStore.edit { it[RATED] = true }
    }

    private companion object {
        val SNOOZED_UNTIL = longPreferencesKey("rate_prompt_snoozed_until")
        val RATED = booleanPreferencesKey("rate_prompt_rated")
    }
}
