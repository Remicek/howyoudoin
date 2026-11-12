package com.devvalor.howyoudoing.chat.data.linkcall

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.devvalor.howyoudoing.chat.domain.LinkCallPreferences
import com.devvalor.howyoudoing.core.domain.app.CallSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreLinkCallPreferences(private val dataStore: DataStore<Preferences>) : LinkCallPreferences, CallSettings {

    override fun observeExplanationHidden(): Flow<Boolean> = dataStore.data.map { it[EXPLANATION_HIDDEN] == true }

    override suspend fun setExplanationHidden(hidden: Boolean) {
        dataStore.edit { it[EXPLANATION_HIDDEN] = hidden }
    }

    override fun observeAskBeforeLettingIn(): Flow<Boolean> = dataStore.data.map { it[ASK_BEFORE_LETTING_IN] == true }

    override suspend fun setAskBeforeLettingIn(ask: Boolean) {
        dataStore.edit { it[ASK_BEFORE_LETTING_IN] = ask }
    }

    private companion object {
        val EXPLANATION_HIDDEN = booleanPreferencesKey("link_call_explanation_hidden")
        val ASK_BEFORE_LETTING_IN = booleanPreferencesKey("link_call_ask_before_letting_in")
    }
}
