package com.devvalor.howyoudoing.networks.presentation.components

import kotlin.experimental.ExperimentalNativeApi

class IosDeveloperOptions : DeveloperOptions {
    @OptIn(ExperimentalNativeApi::class)
    override fun isEnabled(): Boolean = Platform.isDebugBinary
}
