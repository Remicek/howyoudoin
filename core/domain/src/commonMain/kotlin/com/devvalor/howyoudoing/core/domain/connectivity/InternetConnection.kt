package com.devvalor.howyoudoing.core.domain.connectivity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the phone can reach the internet; offline, the app shows what's saved and doesn't try to send. */
interface InternetConnection {
    val isOnline: StateFlow<Boolean>
}

/** For platforms that don't report it (yet): always online, as before. */
object AlwaysOnline : InternetConnection {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
