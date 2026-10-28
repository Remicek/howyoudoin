package com.devvalor.howyoudoing.core.domain.config

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

/**
 * Hardcoded backend for development against howyoudoin-api.
 * `localhost` reaches the Mac from a USB-connected phone via `adb reverse tcp:8080 tcp:8080`
 * (use http://10.0.2.2:8080 on the emulator). Only server mode uses it; the released app connects on the phone.
 */
object BackendConfig {
    const val DEFAULT_HOMESERVER = "http://localhost:8080"

    /** Account seeded by howyoudoin-api on startup (see its application.yml). */
    const val DEFAULT_USERNAME = "alice"
    const val DEFAULT_PASSWORD = "password"

    /** howyoudoin-api serves one provisioning API per network under /bridges/{network}. */
    fun defaultBridgeUrl(homeserverUrl: String, network: ChatNetwork): String =
        "${homeserverUrl.trimEnd('/')}/bridges/${network.name.lowercase()}"
}
