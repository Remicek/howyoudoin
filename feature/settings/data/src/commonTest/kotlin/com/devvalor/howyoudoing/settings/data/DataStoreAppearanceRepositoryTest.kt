package com.devvalor.howyoudoing.settings.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.devvalor.howyoudoing.settings.domain.AppTheme
import com.devvalor.howyoudoing.settings.domain.DarkMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DataStoreAppearanceRepositoryTest {

    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private val dataStore = MemoryDataStore()
    private val repository = DataStoreAppearanceRepository(dataStore)

    @Test
    fun indigo_until_another_theme_is_picked() = runTest {
        assertEquals(AppTheme.INDIGO, repository.observeTheme().first())
        repository.setTheme(AppTheme.GRAPHITE)
        assertEquals(AppTheme.GRAPHITE, repository.observeTheme().first())
    }

    @Test
    fun an_unknown_saved_theme_falls_back_to_indigo() = runTest {
        dataStore.edit { it[stringPreferencesKey("appearance_theme")] = "REMOVED_THEME" }
        assertEquals(AppTheme.INDIGO, repository.observeTheme().first())
    }

    @Test
    fun dark_mode_follows_the_phone_until_switched() = runTest {
        assertEquals(DarkMode.SYSTEM, repository.observeDarkMode().first())
        repository.setDarkMode(DarkMode.ON)
        assertEquals(DarkMode.ON, repository.observeDarkMode().first())
        repository.setDarkMode(DarkMode.OFF)
        assertEquals(DarkMode.OFF, repository.observeDarkMode().first())
    }
}
