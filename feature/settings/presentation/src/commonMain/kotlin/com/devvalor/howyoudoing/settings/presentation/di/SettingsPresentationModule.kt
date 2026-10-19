package com.devvalor.howyoudoing.settings.presentation.di

import com.devvalor.howyoudoing.settings.presentation.crash.CrashReportsViewModel
import com.devvalor.howyoudoing.settings.presentation.licenses.LicensesViewModel
import com.devvalor.howyoudoing.settings.presentation.lock.AppLockSettingsViewModel
import com.devvalor.howyoudoing.settings.presentation.lock.AppLockViewModel
import com.devvalor.howyoudoing.settings.presentation.main.SettingsViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val settingsPresentationModule = module {
    viewModelOf(::SettingsViewModel)
    viewModelOf(::AppLockViewModel)
    viewModelOf(::AppLockSettingsViewModel)
    viewModelOf(::CrashReportsViewModel)
    viewModelOf(::LicensesViewModel)
}
