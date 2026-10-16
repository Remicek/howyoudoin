package com.relay.auth.data.di

import com.relay.auth.data.HomeserverResolver
import com.relay.auth.data.MatrixAuthService
import com.relay.core.domain.auth.AuthService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val authDataModule = module {
    singleOf(::HomeserverResolver)
    singleOf(::MatrixAuthService) bind AuthService::class
}
