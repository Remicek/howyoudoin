package com.relay.networks.presentation.di

import com.relay.networks.presentation.connect.ConnectNetworkViewModel
import com.relay.networks.presentation.list.NetworksViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val networksPresentationModule = module {
    viewModelOf(::NetworksViewModel)
    viewModelOf(::ConnectNetworkViewModel)
}
