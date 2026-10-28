package com.devvalor.howyoudoing.core.data.networking

import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.util.network.UnresolvedAddressException
import kotlinx.coroutines.CancellationException
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
