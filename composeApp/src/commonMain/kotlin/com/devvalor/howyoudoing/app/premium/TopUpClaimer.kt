package com.devvalor.howyoudoing.app.premium

import com.devvalor.howyoudoing.core.domain.premium.CallAllowance
import com.devvalor.howyoudoing.core.domain.premium.Premium
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Hands each paid top-up to the call server, which checks it with Google Play, adds its hours and consumes it. One
 * it couldn't reach is tried again whenever Premium's status is refreshed (the next launch, or opening Premium): the
 * server counts each purchase once however often it's asked.
 */
class TopUpClaimer(premium: Premium, allowance: CallAllowance, scope: CoroutineScope) {
    init {
        // Each time Google Play lists the purchases (checks), even finding the same unclaimed top-ups, try them again
        premium.status.map { it.unclaimedTopUps to it.checks }.distinctUntilChanged().onEach { (tokens, _) ->
            if (!allowance.isAvailable) return@onEach
            tokens.forEach { token ->
                if (allowance.claimTopUp(token) is Result.Success) premium.topUpClaimed(token)
            }
        }.launchIn(scope)
    }
}
