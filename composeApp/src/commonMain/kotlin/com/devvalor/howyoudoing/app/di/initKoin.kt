package com.devvalor.howyoudoing.app.di

import com.devvalor.howyoudoing.auth.data.di.authDataModule
import com.devvalor.howyoudoing.auth.presentation.di.authPresentationModule
import com.devvalor.howyoudoing.chat.data.di.chatDataModule
import com.devvalor.howyoudoing.chat.presentation.di.chatPresentationModule
import com.devvalor.howyoudoing.core.data.di.coreDataModule
import com.devvalor.howyoudoing.core.presentation.di.corePresentationModule
import com.devvalor.howyoudoing.networks.data.di.networksDataModule
import com.devvalor.howyoudoing.networks.presentation.di.networksPresentationModule
import com.devvalor.howyoudoing.settings.data.di.settingsDataModule
import com.devvalor.howyoudoing.settings.presentation.di.settingsPresentationModule
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration

fun initKoin(config: KoinAppDeclaration? = null) {
    startKoin {
        config?.invoke(this)
        modules(
            coreDataModule,
            authDataModule,
            chatDataModule,
            networksDataModule,
            settingsDataModule,
            authPresentationModule,
            chatPresentationModule,
            networksPresentationModule,
            settingsPresentationModule,
            appModule,
            platformAppModule,
            corePresentationModule,
        )
    }
}
