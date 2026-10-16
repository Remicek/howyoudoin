package com.devvalor.howyoudoing.networks.presentation.di

import com.devvalor.howyoudoing.networks.presentation.components.phone.DeviceRegionProvider
import com.devvalor.howyoudoing.networks.presentation.components.phone.IosDeviceRegionProvider
import com.devvalor.howyoudoing.networks.presentation.components.DeveloperOptions
import com.devvalor.howyoudoing.networks.presentation.components.IosDeveloperOptions
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformNetworksPresentationModule: Module = module {
    single<DeveloperOptions> { IosDeveloperOptions() }
    single<DeviceRegionProvider> { IosDeviceRegionProvider() }
}
