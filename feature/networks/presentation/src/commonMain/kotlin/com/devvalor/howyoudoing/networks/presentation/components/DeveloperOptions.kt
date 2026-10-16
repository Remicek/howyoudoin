package com.devvalor.howyoudoing.networks.presentation.components

/** Whether developer-only controls (e.g. the bridge URL override) are shown; true in debug builds. */
fun interface DeveloperOptions {
    fun isEnabled(): Boolean
}
