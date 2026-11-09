package com.devvalor.howyoudoing.core.data.networking

import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SafeLimitedGetTest {

    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK, declareLength: Boolean = true) =
        HttpClientFactory.create(
            MockEngine {
                val headers = if (declareLength) {
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        HttpHeaders.ContentLength to listOf(body.encodeToByteArray().size.toString())
                    )
                } else {
                    // Streamed without a length, like a chunked response
                    headersOf(HttpHeaders.ContentType, "application/json")
                }
                respond(ByteReadChannel(body), status, headers)
            }
        )

    private val oversized = """{"padding":"${"x".repeat(2048)}"}"""

    @Test
    fun readsABodyWithinTheLimit() = runTest {
        val result = client("""{"next_batch":"s1"}""").safeLimitedGet<JsonObject>(1024) { url("https://hs.test/") }
        val json = assertIs<Result.Success<JsonObject>>(result).data
        assertEquals("s1", json["next_batch"]?.jsonPrimitive?.content)
    }

    @Test
    fun refusesABodyOverTheLimitByItsLength() = runTest {
        val result = client(oversized).safeLimitedGet<JsonObject>(1024) { url("https://hs.test/") }
        assertEquals(DataError.Remote.PAYLOAD_TOO_LARGE, assertIs<Result.Failure<DataError.Remote>>(result).error)
    }

    @Test
    fun refusesABodyOverTheLimitWithoutItsLength() = runTest {
        val result = client(oversized, declareLength = false).safeLimitedGet<JsonObject>(1024) {
            url("https://hs.test/")
        }
        assertEquals(DataError.Remote.PAYLOAD_TOO_LARGE, assertIs<Result.Failure<DataError.Remote>>(result).error)
    }

    @Test
    fun aBodyOfExactlyTheLimitIsRead() = runTest {
        val body = """{"a":"${"x".repeat(100)}"}"""
        val result = client(body, declareLength = false).safeLimitedGet<JsonObject>(body.length.toLong()) {
            url("https://hs.test/")
        }
        assertIs<Result.Success<JsonObject>>(result)
    }

    @Test
    fun aFailedStatusIsReportedAsBefore() = runTest {
        val result = client("{}", HttpStatusCode.Unauthorized).safeLimitedGet<JsonObject>(1024) {
            url("https://hs.test/")
        }
        assertEquals(DataError.Remote.UNAUTHORIZED, assertIs<Result.Failure<DataError.Remote>>(result).error)
    }
}
