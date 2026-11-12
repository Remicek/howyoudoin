package com.devvalor.howyoudoing.core.domain.app

import kotlinx.coroutines.flow.Flow

/** Settings for calls through a link, from Settings. */
interface CallSettings {
    /**
     * Whether the person who opens a call's link waits until they're let in. Off by default: whoever opens the link
     * first is in the call. On, a link that leaked from the chat can't be used to listen in.
     */
    fun observeAskBeforeLettingIn(): Flow<Boolean>

    suspend fun setAskBeforeLettingIn(ask: Boolean)
}
