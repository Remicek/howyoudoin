package com.devvalor.howyoudoing.app.ondevice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.devvalor.howyoudoing.app.MainActivity
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceWhatsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps the app's on-device connections (Messenger, WhatsApp, Instagram) alive while it's in the background.
 * Without it Android cuts the sockets as soon as the user switches apps, e.g. to type a WhatsApp pairing code.
 */
class OnDeviceConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Connected on this device"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "On-device connections", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while chats are connected directly from this phone"
                setShowBadge(false)
            }
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("HowYouDoin")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "on_device_connections"
        private const val NOTIFICATION_ID = 4201
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            val intent = Intent(context, OnDeviceConnectionService::class.java).putExtra(EXTRA_TEXT, text)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // Not allowed from the background (Android 12+); it starts the next time the app is opened
                Log.w("OnDeviceConnection", "Couldn't start the connection service now", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OnDeviceConnectionService::class.java))
        }
    }
}

/** Runs [OnDeviceConnectionService] while any on-device network is linked, connecting or pairing. */
class OnDeviceConnectionKeeper(
    context: Context,
    clients: List<OnDeviceMessenger>,
    whatsApp: OnDeviceWhatsApp,
    scope: CoroutineScope
) {
    init {
        val appContext = context.applicationContext
        combine(clients.map { client -> client.state.combineName(client) } + whatsApp.pairing.combineName()) { active ->
            active.filterNotNull()
        }
            .distinctUntilChanged()
            .onEach { active ->
                if (active.isEmpty()) {
                    OnDeviceConnectionService.stop(appContext)
                } else {
                    OnDeviceConnectionService.start(appContext, "Connected on this device: ${active.distinct().joinToString(", ")}")
                }
            }
            .launchIn(scope)
    }

    private fun kotlinx.coroutines.flow.Flow<OnDeviceMessengerState>.combineName(client: OnDeviceMessenger) =
        combine(this, kotlinx.coroutines.flow.flowOf(client.network.displayName)) { state, name ->
            name.takeIf { state != OnDeviceMessengerState.NotLinked }
        }

    private fun kotlinx.coroutines.flow.Flow<Any?>.combineName() =
        combine(this, kotlinx.coroutines.flow.flowOf("WhatsApp")) { pairing, name -> name.takeIf { pairing != null } }
}
