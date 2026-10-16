package com.devvalor.howyoudoing.app.di

import com.devvalor.howyoudoing.app.ondevice.GoOnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformAppModule: Module = module {
    single<OnDeviceMessenger>(createdAtStart = true) { GoOnDeviceMessenger(androidContext(), get()) }
}
