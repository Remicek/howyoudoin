package com.devvalor.howyoudoing.core.data.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.devvalor.howyoudoing.core.data.auth.createDataStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformCoreDataModule: Module = module {
    single<DataStore<Preferences>> {
        createDataStore()
    }
    single<HttpClientEngine> { Darwin.create() }
}
