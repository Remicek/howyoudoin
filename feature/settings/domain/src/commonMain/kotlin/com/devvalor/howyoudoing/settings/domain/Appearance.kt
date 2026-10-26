package com.devvalor.howyoudoing.settings.domain

import kotlinx.coroutines.flow.Flow

/** The app's look, picked in Settings → Appearance. */
enum class AppTheme { INDIGO, LAGOON, SUNSET, GRAPHITE }

interface AppearanceRepository {
    fun observeTheme(): Flow<AppTheme>
    suspend fun setTheme(theme: AppTheme)
}
