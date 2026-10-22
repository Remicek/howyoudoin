package com.devvalor.howyoudoing.app.di

import com.devvalor.howyoudoing.app.MainViewModel
import com.devvalor.howyoudoing.app.ondevice.MatrixAccount
import com.devvalor.howyoudoing.core.domain.auth.MATRIX_ACCOUNT_SESSION
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceGoogleMessages
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceInstagram
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMatrix
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceSignal
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceSlack
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceTelegram
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceWhatsApp
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    viewModelOf(::MainViewModel)
    single<OnDeviceMatrix>(createdAtStart = true) { MatrixAccount(get(), get(named(MATRIX_ACCOUNT_SESSION)), get()) }
    single(createdAtStart = true) {
        ConnectionMonitor(
            clients = listOf(
                get<OnDeviceMessenger>(), get<OnDeviceWhatsApp>(), get<OnDeviceInstagram>(), get<OnDeviceTelegram>(),
                get<OnDeviceSignal>(), get<OnDeviceGoogleMessages>(), get<OnDeviceSlack>(), get<OnDeviceMatrix>()
            ),
            appScope = get()
        )
    }
}
