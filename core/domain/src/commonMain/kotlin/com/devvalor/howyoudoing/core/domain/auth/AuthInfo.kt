package com.devvalor.howyoudoing.core.domain.auth

/**
 * A session: a Matrix homeserver (whose mautrix bridges reach WhatsApp, Messenger and other networks), the demo
 * data set, or [isLocal] — no server at all, with every network connected on the phone itself.
 */
data class AuthInfo(
    val homeserverUrl: String,
    val userId: String,
    val accessToken: String,
    val deviceId: String,
    val isDemo: Boolean = false,
    val isLocal: Boolean = false
) {
    /** Talks to a real homeserver. */
    val hasServer: Boolean get() = !isDemo && !isLocal

    companion object {
        fun demo() = AuthInfo(
            homeserverUrl = "https://demo.howyoudoin.local",
            userId = "@you:howyoudoin.local",
            accessToken = "demo-access-token",
            deviceId = "DEMO",
            isDemo = true
        )

        fun localOnly() = AuthInfo(
            homeserverUrl = "",
            userId = "@you:this-phone",
            accessToken = "",
            deviceId = "LOCAL",
            isLocal = true
        )
    }
}
