package com.devvalor.howyoudoing.chat.data.linkcall

import com.devvalor.howyoudoing.core.data.networking.HttpClientFactory
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpLinkCallsTest {

    private val created = """
        {"room":"r","joinUrl":"https://call.example/call/r","hostUrl":"https://call.example/call/r?host=1#t=tok&u=wss"}
    """.trimIndent()

    @Test
    fun theKeyIsMadeHereAndOnlyGoesInTheFragments() = runTest {
        var requested: String? = null
        val engine = MockEngine { request ->
            requested = "${request.method.value} ${request.url}"
            respond(created, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val calls = HttpLinkCalls(HttpClientFactory.create(engine), "https://call.example/") { ByteArray(it) { 1 } }

        val call = (calls.start(video = true) as Result.Success).data

        assertEquals("${HttpMethod.Post.value} https://call.example/api/calls", requested)
        val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
        assertEquals("https://call.example/call/r#k=$key", call.inviteUrl)
        assertEquals("https://call.example/call/r?host=1#t=tok&u=wss&k=$key&v=1", call.hostUrl)
    }

    @Test
    fun withoutAServerItIsntOffered() = runTest {
        val calls = HttpLinkCalls(HttpClientFactory.create(MockEngine { error("no request") }), "") { ByteArray(it) }
        assertFalse(calls.isAvailable)
        assertEquals(Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE), calls.start(video = false))
    }

    @Test
    fun aBusyServerIsReported() = runTest {
        val engine = MockEngine { respond("""{"error":"Too many calls"}""", HttpStatusCode.TooManyRequests) }
        val calls = HttpLinkCalls(HttpClientFactory.create(engine), "https://call.example") { ByteArray(it) }
        assertTrue(calls.isAvailable)
        assertEquals(Result.Failure(DataError.Remote.TOO_MANY_REQUESTS), calls.start(video = false))
    }
}
