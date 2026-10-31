package com.devvalor.howyoudoing.app.ondevice

import com.devvalor.howyoudoing.core.domain.util.DataError
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteErrorTest {

    @Test
    fun nativeErrorsAreToldApart() {
        mapOf(
            """HTTP 403 Forbidden, {"message": "Cannot send messages to this user", "code": 50007}""" to
                DataError.Remote.FORBIDDEN,
            """Get "https://discord.com/api": dial tcp: lookup discord.com: no such host""" to
                DataError.Remote.NO_INTERNET,
            "read tcp 10.0.0.2:443: i/o timeout" to DataError.Remote.REQUEST_TIMEOUT,
            "HTTP 429 Too Many Requests" to DataError.Remote.TOO_MANY_REQUESTS,
            "HTTP 401 Unauthorized" to DataError.Remote.UNAUTHORIZED,
            "HTTP 502 Bad Gateway" to DataError.Remote.SERVER_ERROR,
            "no Discord user someone" to DataError.Remote.UNKNOWN,
        ).forEach { (message, expected) -> assertEquals(expected, remoteError(message), message) }
        assertEquals(DataError.Remote.UNKNOWN, remoteError(null))
    }
}
