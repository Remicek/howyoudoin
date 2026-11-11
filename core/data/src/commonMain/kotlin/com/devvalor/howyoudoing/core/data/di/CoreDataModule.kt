package com.devvalor.howyoudoing.core.data.di

import com.devvalor.howyoudoing.core.data.app.DataStoreInstallId
import com.devvalor.howyoudoing.core.domain.premium.InstallId
import com.devvalor.howyoudoing.core.domain.config.BuildInfo
import com.devvalor.howyoudoing.core.data.app.DataStoreAppTour
import com.devvalor.howyoudoing.core.data.app.DataStoreHiddenTips
import com.devvalor.howyoudoing.core.data.app.DataStoreNotificationSettings
import com.devvalor.howyoudoing.core.data.app.DataStoreAccountLabels
import com.devvalor.howyoudoing.core.domain.ondevice.AccountLabels
import com.devvalor.howyoudoing.core.data.auth.DataStoreSessionStorage
import com.devvalor.howyoudoing.core.domain.app.AppTour
import com.devvalor.howyoudoing.core.domain.app.HiddenTips
import com.devvalor.howyoudoing.core.domain.app.NotificationSettings
import com.devvalor.howyoudoing.core.data.networking.HttpClientFactory
import com.devvalor.howyoudoing.core.domain.auth.SessionStorage
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import com.devvalor.howyoudoing.core.domain.auth.MATRIX_ACCOUNT_SESSION
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

expect val platformCoreDataModule: Module

val coreDataModule = module {
    includes(platformCoreDataModule)
    single { HttpClientFactory.create(get<HttpClientEngine>(), logRequests = get<BuildInfo>().isDebug()) }
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { DataStoreSessionStorage(get(), get()) } bind SessionStorage::class
    single { DataStoreAppTour(get()) } bind AppTour::class
    single { DataStoreHiddenTips(get()) } bind HiddenTips::class
    single { DataStoreNotificationSettings(get()) } bind NotificationSettings::class
    single { DataStoreAccountLabels(get()) } bind AccountLabels::class
    single { DataStoreInstallId(get()) } bind InstallId::class
    // A Matrix account connected as one of the networks, next to (or without) the app's own server session
    single<SessionStorage>(named(MATRIX_ACCOUNT_SESSION)) {
        DataStoreSessionStorage(get(), get(), keyName = "KEY_MATRIX_ACCOUNT")
    }
}
