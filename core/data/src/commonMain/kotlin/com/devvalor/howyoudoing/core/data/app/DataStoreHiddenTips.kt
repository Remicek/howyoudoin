package com.devvalor.howyoudoing.core.data.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.devvalor.howyoudoing.core.domain.app.HiddenTips
import com.devvalor.howyoudoing.core.domain.app.Tip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreHiddenTips(private val dataStore: DataStore<Preferences>) : HiddenTips {

    override fun observeHidden(tip: Tip): Flow<Boolean> =
        dataStore.data.map { it[keyOf(tip)] == true }.distinctUntilChanged()

    override fun observeAnyHidden(): Flow<Boolean> =
        dataStore.data.map { prefs -> Tip.entries.any { prefs[keyOf(it)] == true } }.distinctUntilChanged()

    override suspend fun hide(tip: Tip) {
        dataStore.edit { it[keyOf(tip)] = true }
    }

    override suspend fun showAll() {
        dataStore.edit { prefs -> Tip.entries.forEach { prefs.remove(keyOf(it)) } }
    }

    private fun keyOf(tip: Tip) = booleanPreferencesKey("tip_hidden_${tip.key}")
}
