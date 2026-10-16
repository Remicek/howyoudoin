package com.devvalor.howyoudoing.networks.presentation.di

import com.devvalor.howyoudoing.networks.presentation.connect.ConnectNetworkViewModel
import com.devvalor.howyoudoing.networks.presentation.list.NetworksViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val networksPresentationModule = module {
    viewModelOf(::NetworksViewModel)
    viewModelOf(::ConnectNetworkViewModel)
}
