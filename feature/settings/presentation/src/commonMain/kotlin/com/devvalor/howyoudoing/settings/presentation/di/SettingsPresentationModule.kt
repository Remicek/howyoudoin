package com.devvalor.howyoudoing.settings.presentation.di

import com.devvalor.howyoudoing.settings.presentation.crash.CrashReportsViewModel
import com.devvalor.howyoudoing.settings.presentation.licenses.LicensesViewModel
import com.devvalor.howyoudoing.settings.presentation.lock.AppLockOfferViewModel
import com.devvalor.howyoudoing.settings.presentation.lock.AppLockSettingsViewModel
import com.devvalor.howyoudoing.settings.presentation.lock.AppLockViewModel
import com.devvalor.howyoudoing.settings.presentation.main.HiddenTipsViewModel
import com.devvalor.howyoudoing.settings.presentation.main.SettingsViewModel
import com.devvalor.howyoudoing.settings.presentation.notifications.NotificationsViewModel
import com.devvalor.howyoudoing.settings.presentation.appearance.AppearanceViewModel
import com.devvalor.howyoudoing.settings.presentation.premium.PremiumViewModel
import com.devvalor.howyoudoing.settings.presentation.telegram.TelegramKeysViewModel
import com.devvalor.howyoudoing.settings.presentation.main.RatePromptViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val settingsPresentationModule = module {
    viewModelOf(::SettingsViewModel)
    viewModelOf(::AppLockViewModel)
    viewModelOf(::AppLockSettingsViewModel)
    viewModelOf(::AppLockOfferViewModel)
    viewModelOf(::CrashReportsViewModel)
    viewModelOf(::LicensesViewModel)
    viewModelOf(::AppearanceViewModel)
    viewModelOf(::TelegramKeysViewModel)
    viewModelOf(::PremiumViewModel)
    viewModelOf(::RatePromptViewModel)
    viewModelOf(::HiddenTipsViewModel)
    viewModelOf(::NotificationsViewModel)
}
