package com.devvalor.howyoudoing.app.di

import com.devvalor.howyoudoing.app.ondevice.IosDataDirectory
import com.devvalor.howyoudoing.app.ondevice.IosMessengerFactory
import com.devvalor.howyoudoing.app.ondevice.IosSecretStore
import com.devvalor.howyoudoing.app.ondevice.IosInboxCache
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceMessenger
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceInboxCache
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformAppModule: Module = module {
    single<OnDeviceInboxCache> { IosInboxCache() }
    single<OnDeviceMessenger>(createdAtStart = true) {
        NativeOnDeviceMessenger(
            factory = IosMessengerFactory(),
            secrets = IosSecretStore(),
            dataDir = IosDataDirectory(),
            scope = get()
        )
    }
}
