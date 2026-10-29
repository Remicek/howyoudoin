package com.devvalor.howyoudoing.app.ondevice

import com.devvalor.howyoudoing.app.R
import android.app.Notification
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
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceLinkedAccount
import kotlinx.coroutines.CoroutineScope
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn

/**
 * Keeps the app's on-device connections (Messenger, WhatsApp, Instagram) alive while it's in the background.
 * Without it Android cuts the sockets as soon as the user switches apps, e.g. to type a WhatsApp pairing code.
 */
class OnDeviceConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification =
            notification(this, intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.connections_default))
        isRunning = true
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        if (follow == null) {
            // Keep the notification in step with what's connected, whoever started the service; from the current list
            // on, as everything may have connected before the service started (it then said "Connecting…" for good)
            val keeper = GlobalContext.get().get<OnDeviceConnectionKeeper>()
            follow = keeper.active
                .onEach { active ->
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        notification(this, keeper.text(active))
                    )
                }
                .launchIn(serviceScope)
        }
        return START_STICKY
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var follow: Job? = null

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "on_device_connections"
        private const val NOTIFICATION_ID = 4201
        private const val EXTRA_TEXT = "text"

        @Volatile
        private var isRunning = false

        private fun notification(context: Context, text: String): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_connections),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = context.getString(R.string.channel_connections_desc)
                    setShowBadge(false)
                }
            )
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("HowYouDoin")
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }

        fun start(context: Context, text: String) {
            if (isRunning) {
                // Already in the foreground: just update its notification (starting again from the background
                // isn't allowed on Android 12+)
                runCatching {
                    context.getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(context, text))
                }
                return
            }
            val intent = Intent(context, OnDeviceConnectionService::class.java).putExtra(EXTRA_TEXT, text)
            try {
                ContextCompat.startForegroundService(context, intent)
                // Running from now on (its onStartCommand follows shortly)
                isRunning = true
            } catch (e: IllegalStateException) {
                // Not allowed from the background (Android 12+); it starts the next time the app is opened
                Log.w("OnDeviceConnection", "Couldn't start the connection service now", e)
            }
        }

        fun stop(context: Context) {
            isRunning = false
            context.stopService(Intent(context, OnDeviceConnectionService::class.java))
        }
    }
}

/** Runs [OnDeviceConnectionService] while any on-device network is linked, connecting or pairing. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceConnectionKeeper(
    context: Context,
    clients: List<OnDeviceMessenger>,
    linkedAccounts: List<OnDeviceLinkedAccount>,
    scope: CoroutineScope
) {
    // Declared before init: its collector may run straight away
    private val appContext = context.applicationContext

    /** Networks currently linked, connecting or pairing; the service's notification follows it. */
    val active = MutableStateFlow<List<String>>(emptyList())

    init {
        combine(clients.map { client -> client.state.combineName(client) } + linkedAccounts.map {
            it.pairing.combineName(
                it
            )
        }) { active ->
            active.filterNotNull()
        }
            .distinctUntilChanged()
            // Each change cancels a pending stop: accounts report their state a moment after start-up, so the
            // service only stops once nothing has been active for a while
            .mapLatest { active ->
                this.active.value = active
                if (active.isNotEmpty()) {
                    OnDeviceConnectionService.start(appContext, text(active))
                } else {
                    delay(STOP_DELAY_MILLIS)
                    OnDeviceConnectionService.stop(appContext)
                }
            }
            .launchIn(scope)
    }

    /**
     * Starts the service now, while the app may still do so from the background (a boot or app-update broadcast is
     * being handled): states that changed before then couldn't start it. If nothing turns out to be linked, it stops
     * again after a while.
     */
    fun startNow() {
        OnDeviceConnectionService.start(appContext, text(active.value))
    }

    fun text(active: List<String>) =
        if (active.isEmpty()) appContext.getString(R.string.connections_connecting) else appContext.getString(
            R.string.connections_connected,
            active.distinct().joinToString(", ")
        )

    private companion object {
        const val STOP_DELAY_MILLIS = 15_000L
    }

    private fun kotlinx.coroutines.flow.Flow<OnDeviceMessengerState>.combineName(client: OnDeviceMessenger) =
        combine(this, kotlinx.coroutines.flow.flowOf(client.network.displayName)) { state, name ->
            name.takeIf { state != OnDeviceMessengerState.NotLinked }
        }

    private fun kotlinx.coroutines.flow.Flow<Any?>.combineName(account: OnDeviceLinkedAccount) =
        combine(
            this,
            kotlinx.coroutines.flow.flowOf(account.network.displayName)
        ) { pairing, name -> name.takeIf { pairing != null } }
}
