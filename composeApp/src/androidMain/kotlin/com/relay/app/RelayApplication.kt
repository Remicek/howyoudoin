package com.relay.app

import android.app.Application
import com.relay.app.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class RelayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@RelayApplication)
            androidLogger()
        }
    }
}
