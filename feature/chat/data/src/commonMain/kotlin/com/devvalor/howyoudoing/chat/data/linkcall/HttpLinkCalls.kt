package com.devvalor.howyoudoing.chat.data.linkcall

import com.devvalor.howyoudoing.chat.domain.LinkCall
import com.devvalor.howyoudoing.chat.domain.LinkCalls
import com.devvalor.howyoudoing.core.data.networking.safeCall
import com.devvalor.howyoudoing.core.domain.premium.CallAllowance
import com.devvalor.howyoudoing.core.domain.premium.CallMinutes
import com.devvalor.howyoudoing.core.domain.premium.InstallId
import com.devvalor.howyoudoing.core.domain.premium.Premium
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.domain.util.map
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * Asks the call server (call-link/ in this repository) for a call. The encryption key is made here and only put in
 * the links' #fragment, which browsers never send to a server: neither the call server nor Cloudflare, which carries
 * the call's audio and video, can read the call. The call's name on the server is a hash of the key
 * ([CallLinkNaming]), so the invite is just the server's address and the key.
 *
 * It also counts the call minutes: each request says which install it is and, with Premium, its Google Play
 * subscription, which the server checks with Google itself.
 *
 * @param serverUrl e.g. https://call.howyoudoin.app; blank when this build has none
 * @param randomBytes cryptographically secure
 * @param sha256 the platform's SHA-256
 * @param askBeforeLettingIn the user's setting: the person who opens the link waits until they're let in
 */
@Suppress("LongParameterList")
class HttpLinkCalls(
    private val httpClient: HttpClient,
    private val serverUrl: String,
    private val installId: InstallId,
    private val premium: Premium,
    private val randomBytes: (Int) -> ByteArray,
    private val sha256: (ByteArray) -> ByteArray,
    private val askBeforeLettingIn: suspend () -> Boolean = { false },
) : LinkCalls, CallAllowance {

    override val isAvailable: Boolean get() = serverUrl.isNotBlank()

    private val base get() = serverUrl.trimEnd('/')

    private suspend fun app(video: Boolean? = null, room: String? = null, askFirst: Boolean = false) = AppRequest(
        install = installId.get(),
        subscription = premium.status.value.subscriptionToken,
        video = video,
        room = room,
        askFirst = askFirst.takeIf { it },
    )

    override suspend fun start(video: Boolean): Result<LinkCall, DataError.Remote> {
        if (!isAvailable) return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        val key = randomBytes(CallLinkNaming.KEY_BYTES)
        val body = app(video = video, room = CallLinkNaming.roomOf(key, sha256), askFirst = askBeforeLettingIn())
        return safeCall<CreatedCall> { httpClient.post("$base/api/calls") { setBody(body) } }
            .map { created -> created.toLinkCall(CallLinkNaming.encode(key), video) }
    }

    override suspend fun minutes(): Result<CallMinutes, DataError.Remote> {
        if (!isAvailable) return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        val body = app()
        return safeCall<MinutesDto> { httpClient.post("$base/api/usage") { setBody(body) } }.map { it.toMinutes() }
    }
}

/** How a call is named and its key written, the same as the call page does it (call-link/web/link.ts). */
object CallLinkNaming {
    const val KEY_BYTES = 16
    private const val ROOM_CHARS = 22

    fun encode(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

    /** The call's name on the server: the first 22 base64url characters of the key's SHA-256. */
    fun roomOf(key: ByteArray, sha256: (ByteArray) -> ByteArray): String = encode(sha256(key)).take(ROOM_CHARS)
}

@Serializable
internal data class AppRequest(
    val install: String,
    val subscription: String? = null,
    val video: Boolean? = null,
    /** The call's name, when starting one. */
    val room: String? = null,
    /** The guest waits until the host lets them in (sent only when on). */
    val askFirst: Boolean? = null,
)

@Serializable
internal data class CreatedCall(
    val joinUrl: String,
    val hostUrl: String,
    val videoAllowed: Boolean = true,
)

@Serializable
internal data class MinutesDto(
    val plan: String,
    val videoLeftMs: Long,
    val audioLeftMs: Long,
    val resetsAt: Long,
)

internal fun MinutesDto.toMinutes() = CallMinutes(
    isPremium = plan == "premium",
    videoLeftMs = videoLeftMs,
    audioLeftMs = audioLeftMs,
    resetsAtMs = resetsAt,
)

/**
 * The key is the invite's whole fragment; the host's link already has one (its token), so there it's k. Without
 * video minutes left the call starts as an audio call.
 */
internal fun CreatedCall.toLinkCall(key: String, video: Boolean): LinkCall {
    val withVideo = video && videoAllowed
    return LinkCall(
        inviteUrl = "$joinUrl#$key",
        hostUrl = "$hostUrl&k=$key&v=${if (withVideo) 1 else 0}",
        isAudioInstead = video && !videoAllowed,
    )
}
