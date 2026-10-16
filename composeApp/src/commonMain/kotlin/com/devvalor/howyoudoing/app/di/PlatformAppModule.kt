package com.devvalor.howyoudoing.app.di

import org.koin.core.module.Module

/** Platform services, e.g. the on-device Messenger client (Android only for now). */
expect val platformAppModule: Module
