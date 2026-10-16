package com.devvalor.howyoudoing.auth.presentation.di

import com.devvalor.howyoudoing.auth.presentation.login.LoginViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val authPresentationModule = module {
    viewModelOf(::LoginViewModel)
}
