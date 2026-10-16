package com.devvalor.howyoudoing.auth.data.di

import com.devvalor.howyoudoing.auth.data.HomeserverResolver
import com.devvalor.howyoudoing.auth.data.MatrixAuthService
import com.devvalor.howyoudoing.core.domain.auth.AuthService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val authDataModule = module {
    singleOf(::HomeserverResolver)
    singleOf(::MatrixAuthService) bind AuthService::class
}
