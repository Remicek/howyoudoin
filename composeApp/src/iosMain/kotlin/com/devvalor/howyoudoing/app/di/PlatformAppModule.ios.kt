package com.devvalor.howyoudoing.app.di

import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.UnsupportedOnDeviceMessenger
import org.koin.core.module.Module
import org.koin.dsl.module

// TODO: gomobile can also build an iOS xcframework (gomobile bind -target=ios) for the same Go code
actual val platformAppModule: Module = module {
    single<OnDeviceMessenger> { UnsupportedOnDeviceMessenger }
}
