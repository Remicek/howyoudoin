package com.devvalor.howyoudoing.app.di

import com.devvalor.howyoudoing.app.MainViewModel
import com.devvalor.howyoudoing.app.backup.RestoreLoginsViewModel
import com.devvalor.howyoudoing.app.backup.BackupOfferViewModel
import com.devvalor.howyoudoing.app.ondevice.MatrixAccount
import com.devvalor.howyoudoing.core.domain.auth.MATRIX_ACCOUNT_SESSION
import com.devvalor.howyoudoing.core.domain.ondevice.CaptchaSolver
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceAccounts
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMatrix
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    viewModelOf(::MainViewModel)
    viewModelOf(::RestoreLoginsViewModel)
    viewModelOf(::BackupOfferViewModel)
    single<OnDeviceMatrix>(createdAtStart = true) { MatrixAccount(get(), get(named(MATRIX_ACCOUNT_SESSION)), get()) }
    singleOf(::OnDeviceAccounts)
    singleOf(::CaptchaSolver)
    single(createdAtStart = true) { ConnectionMonitor(clients = get<OnDeviceAccounts>().all, appScope = get()) }
}
