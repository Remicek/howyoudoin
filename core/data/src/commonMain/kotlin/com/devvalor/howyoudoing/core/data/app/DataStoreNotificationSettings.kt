package com.devvalor.howyoudoing.core.data.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.devvalor.howyoudoing.core.domain.app.NotificationChoices
import com.devvalor.howyoudoing.core.domain.app.NotificationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreNotificationSettings(private val dataStore: DataStore<Preferences>) : NotificationSettings {

    override fun observe(): Flow<NotificationChoices> = dataStore.data
        .map { NotificationChoices(messages = it[MESSAGES] != false, calls = it[CALLS] != false) }
        .distinctUntilChanged()

    override suspend fun setMessages(enabled: Boolean) {
        dataStore.edit { it[MESSAGES] = enabled }
    }

    override suspend fun setCalls(enabled: Boolean) {
        dataStore.edit { it[CALLS] = enabled }
    }

    private companion object {
        val MESSAGES = booleanPreferencesKey("notify_messages")
        val CALLS = booleanPreferencesKey("notify_calls")
    }
}
