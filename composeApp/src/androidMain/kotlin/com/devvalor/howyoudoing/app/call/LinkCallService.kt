package com.devvalor.howyoudoing.app.call

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.devvalor.howyoudoing.app.R

/**
 * Keeps a call through a link going while its screen is in the background: Android stops an app's microphone use
 * there unless a foreground service says it's in a call. Its notification returns to the call or hangs up.
 */
class LinkCallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, LinkCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hangUp = PendingIntent.getActivity(
            this,
            REQUEST_HANG_UP,
            Intent(this, LinkCallActivity::class.java)
                .setAction(LinkCallActivity.ACTION_HANG_UP)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CallNotifier.ONGOING_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(getString(R.string.call_in_progress))
            .setContentText(getString(R.string.call_return))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.call_hang_up), hangUp)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, ONGOING_ID, notification, type)
        } catch (e: SecurityException) {
            Log.w(TAG, "No foreground service for the call: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "LinkCallService"
        private const val ONGOING_ID = 4303
        private const val REQUEST_OPEN = 5
        private const val REQUEST_HANG_UP = 6

        /** While the call screen is open; needs the microphone permission (the screen asks for it first). */
        fun start(context: Context) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, LinkCallService::class.java))
            }.onFailure { Log.w(TAG, "Couldn't start: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkCallService::class.java))
        }
    }
}
