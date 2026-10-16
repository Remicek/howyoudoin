package com.relay.networks.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shows [url] so the user can log in on the network's own website, then reports the
 * values of [cookieNames] once they are all set. Used for bridges whose login step is
 * "cookies" (e.g. mautrix-meta for Messenger).
 */
@Composable
expect fun CookieCaptureWebView(
    url: String,
    userAgent: String?,
    cookieNames: Set<String>,
    onCookiesCaptured: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
)

internal const val COOKIE_POLL_INTERVAL_MILLIS = 1_000L

/** Parses a `name=value; name2=value2` cookie header. */
internal fun parseCookieHeader(header: String?): Map<String, String> =
    header.orEmpty()
        .split(";")
        .mapNotNull { part ->
            val name = part.substringBefore("=", missingDelimiterValue = "").trim()
            if (name.isEmpty()) null else name to part.substringAfter("=").trim()
        }
        .toMap()
