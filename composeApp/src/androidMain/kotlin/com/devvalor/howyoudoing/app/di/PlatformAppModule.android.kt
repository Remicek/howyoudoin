package com.devvalor.howyoudoing.app.di

import com.devvalor.howyoudoing.app.ondevice.AndroidDataDirectory
import com.devvalor.howyoudoing.app.ondevice.AndroidMessengerFactory
import com.devvalor.howyoudoing.app.ondevice.KeystoreSecretStore
import com.devvalor.howyoudoing.app.ondevice.AndroidInboxCache
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceMessenger
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceInboxCache
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformAppModule: Module = module {
    single<OnDeviceInboxCache> { AndroidInboxCache(androidContext()) }
    single<OnDeviceMessenger>(createdAtStart = true) {
        NativeOnDeviceMessenger(
            factory = AndroidMessengerFactory(),
            secrets = KeystoreSecretStore(androidContext()),
            dataDir = AndroidDataDirectory(androidContext()),
            scope = get()
        )
    }
}
