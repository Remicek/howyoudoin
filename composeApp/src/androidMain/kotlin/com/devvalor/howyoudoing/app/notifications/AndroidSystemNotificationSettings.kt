package com.devvalor.howyoudoing.app.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.devvalor.howyoudoing.core.domain.app.NotificationKind
import com.devvalor.howyoudoing.core.domain.app.SystemNotificationSettings

/** Opens Android's notification settings for the app, or for one of its channels. */
class AndroidSystemNotificationSettings(context: Context) : SystemNotificationSettings {

    private val context = context.applicationContext

    override val isSupported = true

    override fun areEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun openApp() = start(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )

    override fun open(kind: NotificationKind) {
        val channel = when (kind) {
            NotificationKind.MESSAGES -> AndroidNotificationPresenter.CHANNEL_ID
            NotificationKind.CALLS -> AndroidNotificationPresenter.CALLS_CHANNEL_ID
        }
        start(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
        )
    }

    private fun start(intent: Intent) {
        // A phone without that settings page still has the app's own
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            val appPage = Uri.fromParts("package", context.packageName, null)
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appPage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
