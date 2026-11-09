package com.devvalor.howyoudoing.core.data.networking

import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.util.network.UnresolvedAddressException
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException

// Any failure to reach the server becomes a typed error the screens can explain, so every exception is caught here
@Suppress("TooGenericExceptionCaught")
suspend inline fun <reified T> safeCall(
    execute: () -> HttpResponse
): Result<T, DataError.Remote> {
    val response = try {
        execute()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return Result.Failure(e.toRemoteError())
    }
    return responseToResult(response)
}

/**
 * Like [safeCall] for a GET whose JSON body is read only up to [maxBytes], so a hostile server can't make the app run
 * out of memory with an endless response. A bigger body fails with [DataError.Remote.PAYLOAD_TOO_LARGE].
 */
@Suppress("TooGenericExceptionCaught")
suspend inline fun <reified T> HttpClient.safeLimitedGet(
    maxBytes: Long,
    crossinline block: HttpRequestBuilder.() -> Unit
): Result<T, DataError.Remote> = try {
    prepareGet { block() }.execute { response ->
        when {
            !response.status.isSuccess() -> Result.Failure(response.status.toRemoteError())
            else -> when (val bytes = response.readBodyLimited(maxBytes)) {
                null -> Result.Failure(DataError.Remote.PAYLOAD_TOO_LARGE)
                else -> Result.Success(HttpClientFactory.json.decodeFromString<T>(bytes.decodeToString()))
            }
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.Failure(e.toRemoteError())
}

/**
 * The body of a streamed response (`prepareGet { }.execute { }`), or null when it is longer than [maxBytes]: refused
 * up front by its Content-Length, or stopped after reading one byte past the limit when the length isn't given.
 */
suspend fun HttpResponse.readBodyLimited(maxBytes: Long): ByteArray? {
    if ((contentLength() ?: 0) > maxBytes) return null
    val channel = bodyAsChannel()
    val bytes = channel.readRemaining(maxBytes + 1).readByteArray()
    return bytes.takeIf { it.size <= maxBytes }
}

/** What a failed request means for the user; unexpected failures are logged. */
@PublishedApi
internal fun Exception.toRemoteError(): DataError.Remote = when (this) {
    is HttpRequestTimeoutException, is SocketTimeoutException -> DataError.Remote.REQUEST_TIMEOUT
    is UnresolvedAddressException -> DataError.Remote.NO_INTERNET
    is SerializationException -> DataError.Remote.SERIALIZATION
    else -> {
        println("HowYouDoin HTTP error: $message")
        DataError.Remote.UNKNOWN
    }
}

suspend inline fun <reified T> responseToResult(
    response: HttpResponse
): Result<T, DataError.Remote> {
    val status = response.status
    if (!status.isSuccess()) return Result.Failure(status.toRemoteError())
    return try {
        Result.Success(response.body<T>())
    } catch (e: NoTransformationFoundException) {
        println("HowYouDoin HTTP response not readable: ${e.message}")
        Result.Failure(DataError.Remote.SERIALIZATION)
    } catch (e: SerializationException) {
        println("HowYouDoin HTTP response not readable: ${e.message}")
        Result.Failure(DataError.Remote.SERIALIZATION)
    }
}

@PublishedApi
internal fun HttpStatusCode.toRemoteError(): DataError.Remote = when (this) {
    HttpStatusCode.BadRequest -> DataError.Remote.BAD_REQUEST
    HttpStatusCode.Unauthorized -> DataError.Remote.UNAUTHORIZED
    HttpStatusCode.Forbidden -> DataError.Remote.FORBIDDEN
    HttpStatusCode.NotFound -> DataError.Remote.NOT_FOUND
    HttpStatusCode.RequestTimeout -> DataError.Remote.REQUEST_TIMEOUT
    HttpStatusCode.Conflict -> DataError.Remote.CONFLICT
    HttpStatusCode.PayloadTooLarge -> DataError.Remote.PAYLOAD_TOO_LARGE
    HttpStatusCode.TooManyRequests -> DataError.Remote.TOO_MANY_REQUESTS
    HttpStatusCode.ServiceUnavailable -> DataError.Remote.SERVICE_UNAVAILABLE
    else -> if (value in SERVER_ERRORS) DataError.Remote.SERVER_ERROR else DataError.Remote.UNKNOWN
}

private val SERVER_ERRORS = HttpStatusCode.InternalServerError.value..<HttpStatusCode.InternalServerError.value + 100
