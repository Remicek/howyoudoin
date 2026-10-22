package com.devvalor.howyoudoing.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.devvalor.howyoudoing.app.MainActivity
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** A notification per network that stopped working; it goes away once the network is back (or disconnected). */
class ConnectionProblemNotifier(context: Context, monitor: ConnectionMonitor, scope: CoroutineScope) {

    private val context = context.applicationContext
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private var shown = emptyMap<Int, ConnectionProblem>()

    init {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Connection problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a connected network stops working"
            }
        )
        monitor.problems.onEach(::show).launchIn(scope)
    }

    private fun show(problems: List<ConnectionProblem>) {
        val current = problems.associateBy { NOTIFICATION_ID_BASE + it.network.ordinal }
        (shown.keys - current.keys).forEach(notifications::cancel)
        current.forEach { (id, problem) ->
            // Only new or changed problems notify again
            if (shown[id] == problem) return@forEach
            val open = PendingIntent.getActivity(
                context, id,
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_NETWORKS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(problem.title)
                .setContentText(problem.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(problem.text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build()
            runCatching { notifications.notify(id, notification) }
        }
        shown = current
    }

    companion object {
        const val EXTRA_OPEN_NETWORKS = "open_networks"
        private const val CHANNEL_ID = "connection_problems"
        private const val NOTIFICATION_ID_BASE = 4500
    }
}
