package com.devvalor.howyoudoing.networks.data.di

import com.devvalor.howyoudoing.networks.data.BridgeEndpointStorage
import com.devvalor.howyoudoing.networks.data.DataStoreOnboardingRepository
import com.devvalor.howyoudoing.networks.data.ProvisioningBridgeRepository
import com.devvalor.howyoudoing.networks.data.SessionAwareBridgeRepository
import com.devvalor.howyoudoing.networks.data.demo.DemoBridgeRepository
import com.devvalor.howyoudoing.networks.domain.BridgeRepository
import com.devvalor.howyoudoing.networks.domain.OnboardingRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val networksDataModule = module {
    singleOf(::BridgeEndpointStorage)
    singleOf(::ProvisioningBridgeRepository)
    singleOf(::DemoBridgeRepository)
    singleOf(::SessionAwareBridgeRepository) bind BridgeRepository::class
    singleOf(::DataStoreOnboardingRepository) bind OnboardingRepository::class
}
