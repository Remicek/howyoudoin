package com.relay.core.domain.auth

/**
 * A Matrix session. Relay talks to a Matrix homeserver; WhatsApp, Messenger and other
 * networks are reached through mautrix bridges running next to that homeserver.
 */
data class AuthInfo(
    val homeserverUrl: String,
    val userId: String,
    val accessToken: String,
    val deviceId: String,
    val isDemo: Boolean = false
) {
    companion object {
        fun demo() = AuthInfo(
            homeserverUrl = "https://demo.relay.local",
            userId = "@you:relay.local",
            accessToken = "demo-access-token",
            deviceId = "DEMO",
            isDemo = true
        )
    }
}
