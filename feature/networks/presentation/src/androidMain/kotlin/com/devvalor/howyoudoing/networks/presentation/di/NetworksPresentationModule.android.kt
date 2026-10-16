package com.devvalor.howyoudoing.networks.presentation.di

import com.devvalor.howyoudoing.networks.presentation.components.phone.AndroidDeviceRegionProvider
import com.devvalor.howyoudoing.networks.presentation.components.phone.DeviceRegionProvider
import org.koin.android.ext.koin.androidContext
import com.devvalor.howyoudoing.networks.presentation.components.DeveloperOptions
import com.devvalor.howyoudoing.networks.presentation.components.AndroidDeveloperOptions
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformNetworksPresentationModule: Module = module {
    single<DeveloperOptions> { AndroidDeveloperOptions(androidContext()) }
    single<DeviceRegionProvider> { AndroidDeviceRegionProvider(androidContext()) }
}
