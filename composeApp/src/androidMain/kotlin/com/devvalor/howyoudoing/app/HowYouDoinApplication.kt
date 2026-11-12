package com.devvalor.howyoudoing.app

import com.devvalor.howyoudoing.core.domain.util.AppLog
import com.devvalor.howyoudoing.app.ondevice.AndroidBackgroundDelivery
import com.devvalor.howyoudoing.app.ondevice.ConnectionWatchdogWorker
import android.app.Application
import com.devvalor.howyoudoing.app.di.initKoin
import com.devvalor.howyoudoing.app.ondevice.StorageKey
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class HowYouDoinApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Nothing for logcat in release builds (see AppLog)
        AppLog.enabled = BuildConfig.DEBUG
        CrashLog.install(this)
        // Before Koin: its eager singletons create the network clients, which read their login files
        StorageKey.install(this)
        initKoin {
            androidContext(this@HowYouDoinApplication)
            androidLogger()
        }
        // Brings the connections back if Android stopped the app and didn't restart it; not when the user turned
        // staying connected off in Settings
        if (AndroidBackgroundDelivery.keepsConnected(this)) {
            ConnectionWatchdogWorker.schedule(this)
        } else {
            ConnectionWatchdogWorker.cancel(this)
        }
    }
}
