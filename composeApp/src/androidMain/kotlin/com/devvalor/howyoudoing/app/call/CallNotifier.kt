package com.devvalor.howyoudoing.app.call

import com.devvalor.howyoudoing.core.domain.app.NotificationSettings
import com.devvalor.howyoudoing.app.R
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.devvalor.howyoudoing.app.settings.CurrentActivity
import com.devvalor.howyoudoing.chat.domain.CallManager
import com.devvalor.howyoudoing.chat.domain.CallPhase
import com.devvalor.howyoudoing.chat.domain.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import android.util.Log
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Follows the call: rings with a full-screen notification for incoming calls, opens the call screen for outgoing
 * ones, and keeps a foreground service (microphone, camera) while a call is going on.
 */
class CallNotifier(
    context: Context,
    callManager: CallManager,
    notificationSettings: NotificationSettings,
    scope: CoroutineScope,
) {

    private val context = context.applicationContext
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private var ringtone: Ringtone? = null

    /** Settings → Notifications → Calls: off, an incoming call doesn't ring. */
    private var isRingingOn = true

    init {
        createChannels()
        notificationSettings.observe().onEach { isRingingOn = it.calls }.launchIn(scope)
        callManager.call
            .map { it?.let { call -> call.callId to call.phase } }
            .distinctUntilChanged()
            .onEach { onChange(callManager.call.value) }
            .launchIn(scope)
    }

    private fun onChange(call: CallState?) {
        val ringing = call?.phase == CallPhase.RINGING
        if (ringing && isRingingOn) ring(call!!) else stopRinging()
        when (call?.phase) {
            CallPhase.CALLING -> {
                openCallScreen()
                CallService.start(context, call.isVideo)
            }

            CallPhase.RINGING -> if (CurrentActivity.activity?.hasWindowFocus() == true) openCallScreen()
            CallPhase.CONNECTING, CallPhase.CONNECTED -> CallService.start(context, call.isVideo && call.isCameraOn)
            CallPhase.ENDED, null -> CallService.stop(context)
        }
    }

    private fun openCallScreen() {
        runCatching { context.startActivity(CallActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun ring(call: CallState) {
        val fullScreen = PendingIntent.getActivity(
            context,
            0,
            CallActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val answer = PendingIntent.getActivity(
            context, 1, CallActivity.intent(context).setAction(CallActivity.ACTION_ANSWER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val decline = PendingIntent.getBroadcast(
            context, 2, Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, RINGING_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(call.peerName)
            .setContentText(
                context.getString(if (call.isVideo) R.string.call_incoming_video else R.string.call_incoming)
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.call_decline), decline)
            .addAction(android.R.drawable.sym_action_call, context.getString(R.string.call_answer), answer)
            .build()
        runCatching { notifications.notify(RINGING_ID, notification) }
        if (ringtone == null) {
            ringtone =
                RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                    ?.apply {
                        audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                        play()
                    }
        }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        notifications.cancel(RINGING_ID)
    }

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(
                RINGING_CHANNEL,
                context.getString(R.string.channel_calls_ringing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_calls_ringing_desc)
                // The ringtone plays separately, so it stops as soon as the call is answered or ends
                setSound(null, null)
            }
        )
        notifications.createNotificationChannel(
            NotificationChannel(
                ONGOING_CHANNEL,
                context.getString(R.string.channel_calls_ongoing),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_calls_ongoing_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val RINGING_CHANNEL = "calls_ringing"
        const val ONGOING_CHANNEL = "calls_ongoing"
        const val RINGING_ID = 4301
        const val ONGOING_ID = 4302
    }
}

/** Keeps the microphone (and camera) working while the call screen isn't in front. */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val withCamera = intent?.getBooleanExtra(EXTRA_CAMERA, false) == true
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            CallActivity.intent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hangUp = PendingIntent.getBroadcast(
            this,
            REQUEST_HANG_UP,
            Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CallNotifier.ONGOING_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(getString(R.string.call_in_progress))
            .setContentText(getString(R.string.call_return))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.call_hang_up), hangUp)
            .build()
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val camera = if (withCamera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or camera
        } else 0
        try {
            ServiceCompat.startForeground(this, CallNotifier.ONGOING_ID, notification, types)
        } catch (e: SecurityException) {
            // Without the permission (e.g. camera not granted) keep at least the microphone
            Log.w(TAG, "Call service without camera: ${e.message}")
            val microphone = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            runCatching { ServiceCompat.startForeground(this, CallNotifier.ONGOING_ID, notification, microphone) }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "CallService"
        private const val REQUEST_OPEN = 3
        private const val REQUEST_HANG_UP = 4
        private const val EXTRA_CAMERA = "camera"

        fun start(context: Context, withCamera: Boolean) {
            // A microphone service can't start without the permission (the call screen asks for it first)
            if (!context.isGranted(Manifest.permission.RECORD_AUDIO)) return
            val camera = withCamera && context.isGranted(Manifest.permission.CAMERA)
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CallService::class.java).putExtra(EXTRA_CAMERA, camera)
                )
            }
        }

        private fun Context.isGranted(permission: String) =
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}

/** Decline / Hang up from a notification. */
class CallActionReceiver : BroadcastReceiver(), KoinComponent {

    private val callManager: CallManager by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_DECLINE -> callManager.decline()
                    ACTION_HANG_UP -> callManager.hangUp()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.devvalor.howyoudoing.call.DECLINE"
        const val ACTION_HANG_UP = "com.devvalor.howyoudoing.call.HANG_UP"
    }
}
