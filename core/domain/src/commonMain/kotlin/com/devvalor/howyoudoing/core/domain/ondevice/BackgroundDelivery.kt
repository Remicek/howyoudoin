package com.devvalor.howyoudoing.core.domain.ondevice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone lets the app keep its networks connected while it isn't being used. Android pauses apps under
 * battery optimization (Doze, and phone makers' own sleeping lists), which cuts the connections, so messages arrive
 * late; the user can exempt the app.
 */
interface BackgroundDelivery {
    /** The app may keep running in the background (re-read by [refresh]). */
    val isAllowed: StateFlow<Boolean>

    /** The user said "not now" to the offer to allow it. */
    val isDeclined: StateFlow<Boolean>

    /** Reads [isAllowed] again, e.g. back from the system's prompt or settings. */
    fun refresh()

    /** Shows the system's prompt to allow it. */
    fun requestAllowed()

    fun decline()

    /** Whether this phone has the [keepsConnected] choice at all (Android). */
    val canKeepConnected: Boolean

    /**
     * The user's choice in Settings, on by default: the app stays connected while it's closed, so messages and calls
     * arrive and notify at once. Off saves the battery that takes: nothing runs in the background (no connection
     * service, no periodic check, no start after a reboot) except while an account is being linked.
     */
    val keepsConnected: StateFlow<Boolean>

    fun setKeepsConnected(on: Boolean)
}

/** Where apps aren't paused like that (iOS here, where the app doesn't run in the background anyway). */
object NoBackgroundDelivery : BackgroundDelivery {
    override val isAllowed: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    override val isDeclined: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override fun refresh() = Unit
    override fun requestAllowed() = Unit
    override fun decline() = Unit
    override val canKeepConnected = false
    override val keepsConnected: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    override fun setKeepsConnected(on: Boolean) = Unit
}
