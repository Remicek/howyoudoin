package com.devvalor.howyoudoing.core.data.networking

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json

object HttpClientFactory {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** [logRequests]: debug builds log each request's method and URL; release builds log nothing. */
    fun create(engine: HttpClientEngine, logRequests: Boolean = false): HttpClient {
        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                // Matrix /sync and bridge "display and wait" steps are long-polls.
                requestTimeoutMillis = LONG_POLL_TIMEOUT.inWholeMilliseconds
                socketTimeoutMillis = LONG_POLL_TIMEOUT.inWholeMilliseconds
                connectTimeoutMillis = 20.seconds.inWholeMilliseconds
            }
            if (logRequests) {
                install(Logging) {
                    logger = object : Logger {
                        override fun log(message: String) {
                            println("HowYouDoin HTTP: $message")
                        }
                    }
                    level = LogLevel.INFO
                }
            }
            defaultRequest {
                contentType(ContentType.Application.Json)
            }
        }
    }
}

private val LONG_POLL_TIMEOUT = 2.minutes
