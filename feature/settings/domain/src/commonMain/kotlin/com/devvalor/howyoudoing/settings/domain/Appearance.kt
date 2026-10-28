package com.devvalor.howyoudoing.settings.domain

import kotlinx.coroutines.flow.Flow

/** The app's look, picked in Settings → Appearance. */
enum class AppTheme { INDIGO, LAGOON, SUNSET, GRAPHITE }

/** Dark mode: as the phone is set, or always off or on. */
enum class DarkMode { SYSTEM, OFF, ON }

interface AppearanceRepository {
    fun observeTheme(): Flow<AppTheme>
    suspend fun setTheme(theme: AppTheme)
    fun observeDarkMode(): Flow<DarkMode>
    suspend fun setDarkMode(mode: DarkMode)
}
