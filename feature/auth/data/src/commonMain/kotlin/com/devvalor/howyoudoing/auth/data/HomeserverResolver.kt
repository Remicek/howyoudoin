package com.devvalor.howyoudoing.auth.data

import com.devvalor.howyoudoing.auth.data.dto.WellKnownDto
import com.devvalor.howyoudoing.core.data.networking.safeCall
import com.devvalor.howyoudoing.core.domain.util.Result
import io.ktor.client.HttpClient
import io.ktor.client.request.get

/**
 * Turns user input ("matrix.org", "https://hs.example.com/", or the server part of an MXID)
 * into a client-server API base URL, following `/.well-known/matrix/client` when present.
 */
class HomeserverResolver(
    private val httpClient: HttpClient
) {

    suspend fun resolve(input: String, username: String): String {
        val server = input.trim().ifBlank {
            username.substringAfter(":", missingDelimiterValue = "")
        }
        val normalized = normalize(server)
        val wellKnown = safeCall<WellKnownDto> {
            httpClient.get("$normalized/.well-known/matrix/client")
        }
        return when (wellKnown) {
            is Result.Success -> wellKnown.data.homeserver?.baseUrl?.let(::normalize) ?: normalized
            is Result.Failure -> normalized
        }
    }

    private fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }
}
