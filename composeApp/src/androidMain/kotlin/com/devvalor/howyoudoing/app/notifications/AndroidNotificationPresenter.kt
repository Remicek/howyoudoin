package com.devvalor.howyoudoing.app.notifications

import com.devvalor.howyoudoing.app.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.devvalor.howyoudoing.app.MainActivity

/** Message notifications on Android; tapping one opens the chat through [NotificationRouter]. */
class AndroidNotificationPresenter(private val context: Context) : MessageNotificationPresenter {

    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_messages_desc)
            }
        )
        // Calls in the networks' chats: their own sound, and Settings → Notifications turns them off apart
        system.createNotificationChannel(
            NotificationChannel(
                CALLS_CHANNEL_ID,
                context.getString(R.string.channel_call_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_call_alerts_desc)
            }
        )
    }

    @Suppress("LongParameterList")
    override fun show(
        chatId: String,
        title: String,
        text: String,
        timestampMs: Long,
        silent: Boolean,
        isCall: Boolean,
    ) {
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context,
            chatId.hashCode(),
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CHAT_ID, chatId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, if (isCall) CALLS_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setWhen(timestampMs)
            .setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(silent)
            .setCategory(if (isCall) NotificationCompat.CATEGORY_MISSED_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            manager.notify(chatId.hashCode(), notification)
        } catch (e: SecurityException) {
            // Permission revoked meanwhile
            Log.w(TAG, "Message notification not shown: ${e.message}")
        }
    }

    override fun cancel(chatId: String) {
        manager.cancel(chatId.hashCode())
    }

    companion object {
        const val CHANNEL_ID = "messages"
        const val CALLS_CHANNEL_ID = "call_alerts"
        const val EXTRA_CHAT_ID = "chatId"
        private const val TAG = "Notifications"
    }
}
