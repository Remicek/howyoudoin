package com.devvalor.howyoudoing.chat.domain

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Calls through a link, for networks whose calls HowYouDoin can't place or answer (all but Matrix): a new call,
 * end-to-end encrypted, that the other person joins from their browser after the link is sent in the chat.
 */
interface LinkCalls {
    /** Whether this build can start one (a call server is set up). */
    val isAvailable: Boolean

    /** Creates the call: [LinkCall.inviteUrl] goes in the chat, [LinkCall.hostUrl] is opened here. */
    suspend fun start(video: Boolean): Result<LinkCall, DataError.Remote>
}

data class LinkCall(
    /** For the other person; carries the encryption key in its #fragment. */
    val inviteUrl: String,
    /** For this device's call screen: the same call, already signed in. */
    val hostUrl: String,
)

/** Whether the explanation shown before a call through a link was turned off ("Don't show this again"). */
interface LinkCallPreferences {
    fun observeExplanationHidden(): Flow<Boolean>
    suspend fun setExplanationHidden(hidden: Boolean)
}

/** Opens the call screen for [LinkCall.hostUrl]. */
fun interface LinkCallLauncher {
    fun open(hostUrl: String)
}

/** Builds without a call server (and iOS for now). */
object NoLinkCalls : LinkCalls {
    override val isAvailable = false
    override suspend fun start(video: Boolean): Result<LinkCall, DataError.Remote> =
        Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
}

object NoLinkCallPreferences : LinkCallPreferences {
    override fun observeExplanationHidden(): Flow<Boolean> = flowOf(false)
    override suspend fun setExplanationHidden(hidden: Boolean) = Unit
}

/** A call through a link suits one-to-one chats on networks that HowYouDoin can't call on itself. */
fun Chat.canCallByLink(): Boolean = network != ChatNetwork.MATRIX && !isGroup
