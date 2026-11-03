package com.devvalor.howyoudoing.core.data.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.devvalor.howyoudoing.core.data.auth.KeystoreSecretCipher
import com.devvalor.howyoudoing.core.data.auth.SecretCipher
import com.devvalor.howyoudoing.core.data.auth.createDataStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformCoreDataModule: Module = module {
    single<DataStore<Preferences>> {
        createDataStore(androidContext())
    }
    single<SecretCipher> { KeystoreSecretCipher() }
    single<HttpClientEngine> { OkHttp.create() }
}
