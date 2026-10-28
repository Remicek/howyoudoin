package com.devvalor.howyoudoing.networks.presentation.di

import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceAccounts
import com.devvalor.howyoudoing.networks.presentation.connect.ConnectNetworkViewModel
import com.devvalor.howyoudoing.networks.presentation.history.EncryptedHistoryViewModel
import com.devvalor.howyoudoing.networks.presentation.list.NetworksViewModel
import com.devvalor.howyoudoing.networks.presentation.tutorial.TutorialViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

expect val platformNetworksPresentationModule: Module

val networksPresentationModule = module {
    includes(platformNetworksPresentationModule)
    singleOf(::OnDeviceAccounts)
    viewModelOf(::NetworksViewModel)
    viewModelOf(::ConnectNetworkViewModel)
    viewModelOf(::TutorialViewModel)
    viewModelOf(::EncryptedHistoryViewModel)
}
