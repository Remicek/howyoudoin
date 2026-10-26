package com.devvalor.howyoudoing.settings.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.devvalor.howyoudoing.settings.domain.AppTheme
import com.devvalor.howyoudoing.settings.domain.AppearanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreAppearanceRepository(private val dataStore: DataStore<Preferences>) : AppearanceRepository {

    override fun observeTheme(): Flow<AppTheme> = dataStore.data.map { prefs ->
        prefs[THEME]?.let { name -> AppTheme.entries.firstOrNull { it.name == name } } ?: AppTheme.INDIGO
    }

    override suspend fun setTheme(theme: AppTheme) {
        dataStore.edit { it[THEME] = theme.name }
    }

    private companion object {
        val THEME = stringPreferencesKey("appearance_theme")
    }
}
