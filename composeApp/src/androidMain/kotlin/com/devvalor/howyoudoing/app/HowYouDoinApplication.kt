package com.devvalor.howyoudoing.app

import android.app.Application
import com.devvalor.howyoudoing.app.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class HowYouDoinApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@HowYouDoinApplication)
            androidLogger()
        }
    }
}
