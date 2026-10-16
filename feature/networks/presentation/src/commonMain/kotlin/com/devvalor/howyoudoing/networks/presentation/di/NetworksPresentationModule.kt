package com.devvalor.howyoudoing.networks.presentation.di

import com.devvalor.howyoudoing.networks.presentation.connect.ConnectNetworkViewModel
import com.devvalor.howyoudoing.networks.presentation.list.NetworksViewModel
import com.devvalor.howyoudoing.networks.presentation.tutorial.TutorialViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

expect val platformNetworksPresentationModule: Module

val networksPresentationModule = module {
    includes(platformNetworksPresentationModule)
    viewModelOf(::NetworksViewModel)
    viewModelOf(::ConnectNetworkViewModel)
    viewModelOf(::TutorialViewModel)
}
