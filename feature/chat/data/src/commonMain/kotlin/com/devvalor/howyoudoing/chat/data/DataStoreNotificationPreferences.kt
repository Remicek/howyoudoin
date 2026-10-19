package com.devvalor.howyoudoing.chat.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.devvalor.howyoudoing.chat.domain.NotificationPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreNotificationPreferences(
    private val dataStore: DataStore<Preferences>
) : NotificationPreferences {

    override fun observeMutedChats(): Flow<Set<String>> =
        dataStore.data.map { it[MUTED_CHATS].orEmpty() }

    override suspend fun setMuted(chatId: String, muted: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[MUTED_CHATS].orEmpty()
            prefs[MUTED_CHATS] = if (muted) current + chatId else current - chatId
        }
    }

    private companion object {
        val MUTED_CHATS = stringSetPreferencesKey("muted_chats")
    }
}
