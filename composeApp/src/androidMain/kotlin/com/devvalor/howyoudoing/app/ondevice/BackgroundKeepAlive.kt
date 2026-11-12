package com.devvalor.howyoudoing.app.ondevice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.devvalor.howyoudoing.core.domain.ondevice.BackgroundDelivery
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/** Android's battery optimization: the app asks to be left out of it, so its connections survive the phone sleeping. */
class AndroidBackgroundDelivery(context: Context) : BackgroundDelivery {

    private val context = context.applicationContext
    private val power = this.context.getSystemService(PowerManager::class.java)
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _isAllowed = MutableStateFlow(read())
    override val isAllowed: StateFlow<Boolean> = _isAllowed.asStateFlow()

    private val _isDeclined = MutableStateFlow(prefs.getBoolean(KEY_DECLINED, false))
    override val isDeclined: StateFlow<Boolean> = _isDeclined.asStateFlow()

    private fun read() = power.isIgnoringBatteryOptimizations(context.packageName)

    override fun refresh() {
        _isAllowed.value = read()
    }

    // A messaging app that keeps its own connections is what this prompt is meant for
    @SuppressLint("BatteryLife")
    override fun requestAllowed() {
        val prompt = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        for (intent in listOf(prompt, list)) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    override fun decline() {
        prefs.edit().putBoolean(KEY_DECLINED, true).apply()
        _isDeclined.value = true
    }

    override val canKeepConnected = true

    private val _keepsConnected = MutableStateFlow(keepsConnected(this.context))
    override val keepsConnected: StateFlow<Boolean> = _keepsConnected.asStateFlow()

    override fun setKeepsConnected(on: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_CONNECTED, on).apply()
        _keepsConnected.value = on
        // The connection service follows this (OnDeviceConnectionKeeper); the periodic check goes with it
        if (on) ConnectionWatchdogWorker.schedule(context) else ConnectionWatchdogWorker.cancel(context)
    }

    companion object {
        private const val PREFS = "background_delivery"
        private const val KEY_DECLINED = "declined"
        private const val KEY_KEEP_CONNECTED = "keep_connected"

        /** The Settings choice, for code that runs before Koin (the app's start, a boot broadcast). */
        fun keepsConnected(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_CONNECTED, true)
    }
}

/**
 * Every 15 minutes (when online), WorkManager runs this. If Android had stopped the app and not brought it back,
 * running it starts the app, whose networks connect by themselves; networks that lost their connection and are still
 * without it start over.
 */
class ConnectionWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Turned off in Settings (the work is cancelled then; this covers one already on its way)
        if (!AndroidBackgroundDelivery.keepsConnected(applicationContext)) return Result.success()
        val koin = GlobalContext.getOrNull() ?: return Result.success()
        koin.get<OnDeviceConnectionKeeper>().ensureRunning()
        koin.get<ConnectionMonitor>().reconnectDropped()
        return Result.success()
    }

    companion object {
        private const val NAME = "connection_watchdog"
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ConnectionWatchdogWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}

/**
 * When the phone is online again (back from airplane mode, Wi-Fi to mobile data), networks still without their
 * connection a few seconds later start over at once instead of at their next retry, which can be minutes away.
 */
class OnlineReconnector(context: Context, private val monitor: ConnectionMonitor, private val scope: CoroutineScope) {

    private var pending: Job? = null

    init {
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    pending?.cancel()
                    pending = scope.launch {
                        // Most reconnect by themselves on a new network; only the ones that don't
                        delay(SETTLE_MILLIS)
                        monitor.reconnectDropped()
                    }
                }
            }
        )
    }

    private companion object {
        const val SETTLE_MILLIS = 5_000L
    }
}
