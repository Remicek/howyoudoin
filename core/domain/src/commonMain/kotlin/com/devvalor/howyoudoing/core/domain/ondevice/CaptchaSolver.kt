package com.devvalor.howyoudoing.core.domain.ondevice

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A CAPTCHA a network wants solved before it does something (Discord, for a friend request from a new account). */
data class CaptchaChallenge(
    val network: ChatNetwork,
    /** "hcaptcha" (the only kind shown so far). */
    val service: String,
    val siteKey: String,
    /** Extra data the CAPTCHA has to be given (hCaptcha's rqdata), if any. */
    val rqData: String?,
    /** The page it belongs to: the CAPTCHA only works there. */
    val pageUrl: String
)

/**
 * Hands CAPTCHAs from the network clients to the screen that shows them (one at a time) and the answers back.
 */
class CaptchaSolver {

    private val mutex = Mutex()
    private var answer: CompletableDeferred<String?>? = null

    private val _pending = MutableStateFlow<CaptchaChallenge?>(null)
    val pending: StateFlow<CaptchaChallenge?> = _pending.asStateFlow()

    /** Shows [challenge] and waits for the user: the token, or null if they gave up. */
    suspend fun solve(challenge: CaptchaChallenge): String? = mutex.withLock {
        val deferred = CompletableDeferred<String?>()
        answer = deferred
        _pending.value = challenge
        try {
            deferred.await()
        } finally {
            _pending.value = null
            answer = null
        }
    }

    /** The CAPTCHA screen's result: the token, or null when closed. */
    fun submit(token: String?) {
        answer?.complete(token)
    }
}
