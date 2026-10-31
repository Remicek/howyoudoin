package com.devvalor.howyoudoing.app.notifications

import com.devvalor.howyoudoing.app.R
import com.devvalor.howyoudoing.chat.presentation.model.text
import com.devvalor.howyoudoing.chat.presentation.model.title
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.devvalor.howyoudoing.app.MainActivity
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * One notification naming the networks that stopped working; it goes away once they're back (or disconnected).
 */
class ConnectionProblemNotifier(context: Context, monitor: ConnectionMonitor, scope: CoroutineScope) {

    private val context = context.applicationContext
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private var shown = emptyList<ConnectionProblem>()

    init {
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_problems),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_problems_desc)
            }
        )
        // Earlier versions showed one per network
        ChatNetwork.entries.forEach { runCatching { notifications.cancel(NOTIFICATION_ID + it.ordinal) } }
        monitor.problems.onEach(::show).launchIn(scope)
    }

    private suspend fun show(problems: List<ConnectionProblem>) {
        // Only new or changed problems notify again
        if (problems == shown) return
        shown = problems
        if (problems.isEmpty()) {
            notifications.cancel(NOTIFICATION_ID)
            return
        }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_NETWORKS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = problems.text.asStringAsync()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(problems.title.asStringAsync())
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        runCatching { notifications.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val EXTRA_OPEN_NETWORKS = "open_networks"
        private const val CHANNEL_ID = "connection_problems"
        private const val NOTIFICATION_ID = 4500
    }
}
