package com.devvalor.howyoudoing.settings.data.di

import com.devvalor.howyoudoing.settings.data.DataStoreAppLockRepository
import com.devvalor.howyoudoing.settings.domain.AppLockRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** PinHasher, BiometricAuthenticator and CrashReports come from the platform (app module). */
val settingsDataModule = module {
    singleOf(::DataStoreAppLockRepository) bind AppLockRepository::class
}
