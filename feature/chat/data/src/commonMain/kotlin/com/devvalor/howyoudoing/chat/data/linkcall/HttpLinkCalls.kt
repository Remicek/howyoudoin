package com.devvalor.howyoudoing.chat.data.linkcall

import com.devvalor.howyoudoing.chat.domain.LinkCall
import com.devvalor.howyoudoing.chat.domain.LinkCalls
import com.devvalor.howyoudoing.core.data.networking.safeCall
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.domain.util.map
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * Asks the call server (call-link/ in this repository) for a room. The encryption key is made here and only put in
 * the links' #fragment, which browsers never send to a server: neither the call server nor LiveKit can read the call.
 *
 * @param serverUrl e.g. https://call.howyoudoin.app; blank when this build has none
 * @param randomBytes cryptographically secure
 */
class HttpLinkCalls(
    private val httpClient: HttpClient,
    private val serverUrl: String,
    private val randomBytes: (Int) -> ByteArray,
) : LinkCalls {

    override val isAvailable: Boolean get() = serverUrl.isNotBlank()

    override suspend fun start(video: Boolean): Result<LinkCall, DataError.Remote> {
        if (!isAvailable) return Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
        return safeCall<CreatedCall> { httpClient.post("${serverUrl.trimEnd('/')}/api/calls") }
            .map { created -> created.toLinkCall(newKey(), video) }
    }

    private fun newKey(): String = Base64.UrlSafe
        .withPadding(Base64.PaddingOption.ABSENT)
        .encode(randomBytes(KEY_BYTES))

    private companion object {
        const val KEY_BYTES = 32
    }
}

@Serializable
internal data class CreatedCall(val room: String, val joinUrl: String, val hostUrl: String)

/** The key joins both links' fragments; the host's link already has one (its token). */
internal fun CreatedCall.toLinkCall(key: String, video: Boolean) = LinkCall(
    inviteUrl = "$joinUrl#k=$key",
    hostUrl = "$hostUrl&k=$key&v=${if (video) 1 else 0}",
)
