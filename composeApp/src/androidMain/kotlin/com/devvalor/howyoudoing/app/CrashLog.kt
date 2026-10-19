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
 * Keeps the last crash on the phone (nothing is sent anywhere). On the next launch a notification offers to share
 * the report, e.g. by email, so the user decides who sees it.
 */
object CrashLog {

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { reportFile(context).writeText(describe(context, thread, error)) }
            previous?.uncaughtException(thread, error)
        }
        offerLastReport(context)
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

    private fun offerLastReport(context: Context) {
        val report = reportFile(context).takeIf { it.exists() } ?: return
        // Hand the report to other apps through the FileProvider's shared folder, then forget it
        val shared = File(context.cacheDir, "shared").apply { mkdirs() }.resolve("crash-report.txt")
        runCatching { report.copyTo(shared, overwrite = true); report.delete() }.onFailure { return }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "HowYouDoin crash report")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val open = PendingIntent.getActivity(
            context, 0, Intent.createChooser(share, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
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

    private const val CHANNEL_ID = "crash_reports"
    private const val NOTIFICATION_ID = 4041
}
