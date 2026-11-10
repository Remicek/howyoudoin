package com.devvalor.howyoudoing.chat.data.linkcall

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.devvalor.howyoudoing.chat.domain.LinkCallPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreLinkCallPreferences(private val dataStore: DataStore<Preferences>) : LinkCallPreferences {

    override fun observeExplanationHidden(): Flow<Boolean> = dataStore.data.map { it[EXPLANATION_HIDDEN] == true }

    override suspend fun setExplanationHidden(hidden: Boolean) {
        dataStore.edit { it[EXPLANATION_HIDDEN] = hidden }
    }

    private companion object {
        val EXPLANATION_HIDDEN = booleanPreferencesKey("link_call_explanation_hidden")
    }
}
