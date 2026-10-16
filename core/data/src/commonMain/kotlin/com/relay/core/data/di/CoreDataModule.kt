package com.relay.core.data.di

import com.relay.core.data.auth.DataStoreSessionStorage
import com.relay.core.data.networking.HttpClientFactory
import com.relay.core.domain.auth.SessionStorage
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

expect val platformCoreDataModule: Module

val coreDataModule = module {
    includes(platformCoreDataModule)
    single { HttpClientFactory.create(get<HttpClientEngine>()) }
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    singleOf(::DataStoreSessionStorage) bind SessionStorage::class
}
