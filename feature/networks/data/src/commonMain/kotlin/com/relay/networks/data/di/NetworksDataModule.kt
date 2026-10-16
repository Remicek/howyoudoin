package com.relay.networks.data.di

import com.relay.networks.data.BridgeEndpointStorage
import com.relay.networks.data.ProvisioningBridgeRepository
import com.relay.networks.data.SessionAwareBridgeRepository
import com.relay.networks.data.demo.DemoBridgeRepository
import com.relay.networks.domain.BridgeRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val networksDataModule = module {
    singleOf(::BridgeEndpointStorage)
    singleOf(::ProvisioningBridgeRepository)
    singleOf(::DemoBridgeRepository)
    singleOf(::SessionAwareBridgeRepository) bind BridgeRepository::class
}
