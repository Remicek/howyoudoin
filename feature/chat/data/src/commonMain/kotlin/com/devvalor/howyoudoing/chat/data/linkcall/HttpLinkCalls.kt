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
 * the call's audio and video, can read the call.
 *
 * It also counts the call minutes: each request says which install it is and, with Premium, its Google Play
 * subscription, which the server checks with Google itself.
 *
 * @param serverUrl e.g. https://call.howyoudoin.app; blank when this build has none
 * @param randomBytes cryptographically secure
 */
class HttpLinkCalls(
    private val httpClient: HttpClient,
    private val serverUrl: String,
    private val installId: InstallId,
    private val premium: Premium,
    private val randomBytes: (Int) -> ByteArray,
) : LinkCalls, CallAllowance {

    override val isAvailable: Boolean get() = serverUrl.isNotBlank()

    private val base get() = serverUrl.trimEnd('/')

    private suspend fun app(video: Boolean? = null) = AppRequest(
        install = installId.get(),
        subscription = premium.status.value.subscriptionToken,
        video = video,
    )

    override suspend fun start(video: Boolean): Result<LinkCall, DataError.Remote> {
        if (!isAvailable) return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        val body = app(video = video)
        return safeCall<CreatedCall> { httpClient.post("$base/api/calls") { setBody(body) } }
            .map { created -> created.toLinkCall(newKey(), video) }
    }

    override suspend fun minutes(): Result<CallMinutes, DataError.Remote> {
        if (!isAvailable) return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        val body = app()
        return safeCall<MinutesDto> { httpClient.post("$base/api/usage") { setBody(body) } }.map { it.toMinutes() }
    }

    private fun newKey(): String = Base64.UrlSafe
        .withPadding(Base64.PaddingOption.ABSENT)
        .encode(randomBytes(KEY_BYTES))

    private companion object {
        const val KEY_BYTES = 32
    }
}

@Serializable
internal data class AppRequest(
    val install: String,
    val subscription: String? = null,
    val video: Boolean? = null,
)

@Serializable
internal data class CreatedCall(
    val room: String,
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
 * The key joins both links' fragments; the host's link already has one (its token). Without video minutes left the
 * call starts as an audio call.
 */
internal fun CreatedCall.toLinkCall(key: String, video: Boolean): LinkCall {
    val withVideo = video && videoAllowed
    return LinkCall(
        inviteUrl = "$joinUrl#k=$key",
        hostUrl = "$hostUrl&k=$key&v=${if (withVideo) 1 else 0}",
        isAudioInstead = video && !videoAllowed,
    )
}
