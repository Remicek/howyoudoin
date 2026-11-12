package com.devvalor.howyoudoing.app.ondevice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * After a reboot or an app update nothing would reconnect the on-device chats until the app is opened; this wakes
 * the app so [OnDeviceConnectionKeeper] reconnects linked accounts and starts the connection service.
 */
class StartOnBootReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        val isStart = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        // Not when staying connected is off in Settings: the chats connect when the app is opened
        if (isStart && AndroidBackgroundDelivery.keepsConnected(context)) {
            // Android lets the service start only while this broadcast is being handled
            get<OnDeviceConnectionKeeper>().startNow()
        }
    }
}
