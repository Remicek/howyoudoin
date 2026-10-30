package com.devvalor.howyoudoing.app

import android.app.Application
import com.devvalor.howyoudoing.app.di.initKoin
import com.devvalor.howyoudoing.app.ondevice.StorageKey
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class HowYouDoinApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Before Koin: its eager singletons create the network clients, which read their login files
        StorageKey.install(this)
        initKoin {
            androidContext(this@HowYouDoinApplication)
            androidLogger()
        }
    }
}
