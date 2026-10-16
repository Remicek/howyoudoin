package com.devvalor.howyoudoing.app.di

import com.devvalor.howyoudoing.app.MainViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    viewModelOf(::MainViewModel)
}
