package com.relay.app.di

import com.relay.auth.data.di.authDataModule
import com.relay.auth.presentation.di.authPresentationModule
import com.relay.chat.data.di.chatDataModule
import com.relay.chat.presentation.di.chatPresentationModule
import com.relay.core.data.di.coreDataModule
import com.relay.core.presentation.di.corePresentationModule
import com.relay.networks.data.di.networksDataModule
import com.relay.networks.presentation.di.networksPresentationModule
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
            authPresentationModule,
            chatPresentationModule,
            networksPresentationModule,
            appModule,
            corePresentationModule,
        )
    }
}
