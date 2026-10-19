package com.devvalor.howyoudoing.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash on the phone (nothing is sent anywhere). Settings → Crash reports shows, shares or deletes
 * it; if the user allows, a notification on the next launch offers to share it, so the user decides who sees it.
 */
object CrashLog {

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                reportFile(context).writeText(describe(context, thread, error))
                prefs(context).edit().putBoolean(KEY_OFFERED, false).commit()
            }
            previous?.uncaughtException(thread, error)
        }
        offerLastReport(context)
    }

    /** The last report's time and text, if any. */
    fun lastReport(context: Context): Pair<Long, String>? {
        val file = reportFile(context).takeIf { it.exists() } ?: return null
        return runCatching { file.lastModified() to file.readText() }.getOrNull()
    }

    fun deleteReport(context: Context) {
        reportFile(context).delete()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun offersAfterCrash(context: Context): Boolean = prefs(context).getBoolean(KEY_OFFERS, true)

    fun setOffersAfterCrash(context: Context, offers: Boolean) {
        prefs(context).edit().putBoolean(KEY_OFFERS, offers).apply()
    }

    /** Opens the share sheet with the report as a text file. */
    fun share(context: Context, text: String) {
        context.startActivity(shareChooser(context, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun shareChooser(context: Context, text: String): Intent {
        // Hand the report to other apps through the FileProvider's shared folder
        val shared = File(context.cacheDir, "shared").apply { mkdirs() }.resolve("crash-report.txt")
        shared.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "HowYouDoin crash report")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share crash report")
    }

    private fun describe(context: Context, thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return buildString {
            appendLine("HowYouDoin $version crash report")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Time: ${java.util.Date()}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
        }
    }

    /** Once per crash, if the user allows it. */
    private fun offerLastReport(context: Context) {
        val prefs = prefs(context)
        if (!offersAfterCrash(context) || prefs.getBoolean(KEY_OFFERED, true)) return
        val (_, text) = lastReport(context) ?: return
        prefs.edit().putBoolean(KEY_OFFERED, true).apply()
        val open = runCatching {
            PendingIntent.getActivity(
                context, 0, shareChooser(context, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }.getOrNull() ?: return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Crash reports", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("HowYouDoin stopped last time")
            .setContentText("Tap to share the crash report")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications not allowed
        }
    }

    private fun reportFile(context: Context) = File(context.filesDir, "last-crash.txt")

    private fun prefs(context: Context) = context.getSharedPreferences("crash_reports", Context.MODE_PRIVATE)

    private const val KEY_OFFERS = "offers_after_crash"
    /** The current report was already offered (or none was written since). */
    private const val KEY_OFFERED = "offered"
    private const val CHANNEL_ID = "crash_reports"
    private const val NOTIFICATION_ID = 4041
}
