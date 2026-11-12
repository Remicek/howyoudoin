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
import io.ktor.http.content.TextContent
import com.devvalor.howyoudoing.core.domain.premium.CallMinutes
import com.devvalor.howyoudoing.core.domain.premium.InstallId
import com.devvalor.howyoudoing.core.domain.premium.NoPremium
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpLinkCallsTest {

    private val install = InstallId { "0123456789abcdef0123456789abcdef" }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** Stands in for SHA-256 (the real derivation is checked against the page in CallLinkNamingTest). */
    private val fakeSha256: (ByteArray) -> ByteArray = { key -> ByteArray(32) { i -> (key[i % key.size] + i).toByte() } }

    private fun calls(
        engine: MockEngine,
        url: String = "https://call.example",
        bytes: (Int) -> ByteArray = { ByteArray(it) },
    ) = HttpLinkCalls(HttpClientFactory.create(engine), url, install, NoPremium, bytes, fakeSha256)

    private val created = """
        {"joinUrl":"https://call.example/","hostUrl":"https://call.example/?host=1#t=tok&u=wss"}
    """.trimIndent()

    @Test
    fun theKeyIsMadeHereAndOnlyGoesInTheFragments() = runTest {
        var requested: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            requested = "${request.method.value} ${request.url}"
            body = (request.body as TextContent).text
            respond(created, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val calls = calls(engine, "https://call.example/") { ByteArray(it) { 1 } }

        val call = (calls.start(video = true) as Result.Success).data

        assertEquals("${HttpMethod.Post.value} https://call.example/api/calls", requested)
        // Whose minutes and the call's name (a hash of the key); never the key
        val key = "AQEBAQEBAQEBAQEBAQEBAQ"
        val room = CallLinkNaming.roomOf(ByteArray(16) { 1 }, fakeSha256)
        assertEquals(22, room.length)
        assertEquals("""{"install":"0123456789abcdef0123456789abcdef","video":true,"room":"$room"}""", body)
        assertEquals("https://call.example/#$key", call.inviteUrl)
        assertEquals("https://call.example/?host=1#t=tok&u=wss&k=$key&v=1", call.hostUrl)
    }

    @Test
    fun withoutAServerItIsntOffered() = runTest {
        val calls = calls(MockEngine { error("no request") }, url = "")
        assertFalse(calls.isAvailable)
        assertEquals(Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE), calls.start(video = false))
    }

    @Test
    fun aBusyServerIsReported() = runTest {
        val engine = MockEngine { respond("""{"error":"Too many calls"}""", HttpStatusCode.TooManyRequests) }
        val calls = calls(engine)
        assertTrue(calls.isAvailable)
        assertEquals(Result.Failure(DataError.Remote.TOO_MANY_REQUESTS), calls.start(video = false))
    }

    @Test
    fun noMinutesLeftIsReported() = runTest {
        val engine = MockEngine {
            respond("""{"error":"No call minutes left","code":"no_minutes"}""", HttpStatusCode.PaymentRequired)
        }
        val calls = calls(engine)
        assertEquals(Result.Failure(DataError.Remote.PAYMENT_REQUIRED), calls.start(video = true))
    }

    @Test
    fun withoutVideoMinutesTheCallIsAudio() = runTest {
        val engine = MockEngine {
            respond(created.replace("}", ",\"videoAllowed\":false}"), HttpStatusCode.OK, json)
        }
        val calls = calls(engine)
        val call = (calls.start(video = true) as Result.Success).data
        assertTrue(call.isAudioInstead)
        assertTrue(call.hostUrl.endsWith("&v=0"))
    }

    @Test
    fun theMinutesLeftAreRead() = runTest {
        val engine = MockEngine {
            respond(
                """{"plan":"free","videoLeftMs":2520000,"audioLeftMs":36000000,""" +
                    """"resetsAt":1796083200000}""",
                HttpStatusCode.OK,
                json,
            )
        }
        val calls = calls(engine)
        val minutes = (calls.minutes() as Result.Success).data
        assertEquals(CallMinutes(false, 2_520_000, 36_000_000, 1_796_083_200_000), minutes)
    }
}
