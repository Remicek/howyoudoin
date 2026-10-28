package com.devvalor.howyoudoing.settings.domain

import kotlinx.coroutines.flow.Flow

/** The last crash, kept only on the phone. */
data class CrashReport(val timeMillis: Long, val text: String)

/**
 * Crash reports stay on the phone; nothing is uploaded. The user can read the last one, share it (e.g. by email
 * to the developer) or delete it.
 */
interface CrashReports {
    fun observeLastReport(): Flow<CrashReport?>

    /** Opens the system share sheet with the report. */
    fun share(report: CrashReport)

    suspend fun delete()

    /** Whether a notification offers to share the report after a crash. */
    fun observeOffersAfterCrash(): Flow<Boolean>

    suspend fun setOffersAfterCrash(offers: Boolean)
}

/** What the About screen shows about this build. */
data class AppInfo(
    val versionName: String,
    /** Where the app's source code is published (the AGPL requires offering it). */
    val sourceCodeUrl: String,
    /** The full privacy policy, which Google Play requires to be reachable from inside the app. */
    val privacyPolicyUrl: String,
)
