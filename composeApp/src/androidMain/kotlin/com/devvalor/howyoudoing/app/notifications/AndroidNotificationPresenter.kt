package com.devvalor.howyoudoing.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.devvalor.howyoudoing.app.MainActivity

/** Message notifications on Android; tapping one opens the chat through [NotificationRouter]. */
class AndroidNotificationPresenter(private val context: Context) : MessageNotificationPresenter {

    private val manager = NotificationManagerCompat.from(context)

    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages from your chats"
            }
        )
    }

    override fun show(chatId: String, title: String, text: String, timestampMs: Long) {
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context,
            chatId.hashCode(),
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CHAT_ID, chatId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setWhen(timestampMs)
            .setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            manager.notify(chatId.hashCode(), notification)
        } catch (e: SecurityException) {
            // Permission revoked meanwhile
        }
    }

    override fun cancel(chatId: String) {
        manager.cancel(chatId.hashCode())
    }

    companion object {
        const val CHANNEL_ID = "messages"
        const val EXTRA_CHAT_ID = "chatId"
    }
}
