package com.devvalor.howyoudoing.networks.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shows [url] so the user can log in on the network's own website, then reports the
 * values of [cookieNames] once they are all set. Used for bridges whose login step is
 * "cookies" (e.g. mautrix-meta for Messenger).
 *
 * [extractScript], if given, runs in the page on every poll and
 * returns a JSON object (as a string) of more values, e.g. a token from the page's storage; they count as cookies.
 */
@Composable
expect fun CookieCaptureWebView(
    url: String,
    userAgent: String?,
    cookieNames: Set<String>,
    onCookiesCaptured: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
    extractScript: String? = null,
)

internal const val COOKIE_POLL_INTERVAL_MILLIS = 1_000L

/**
 * The web page to open instead of a link meant for an app, or null to stay put. After signing in, Slack hands the
 * session to its desktop app ("slack://T…/magic-login/…"); its web client for that workspace is signed in too, and
 * it's where the session token is read from.
 */
internal fun webFallbackFor(url: String): String? =
    Regex("^slack://(T[A-Z0-9]+)/").find(url)?.let { "https://app.slack.com/client/${it.groupValues[1]}" }

/**
 * Finds what the user has to act on on a login page: an open dialog (e.g. a cookie notice, which covers the rest),
 * else the form with a password field, another visible form, or a "Continue" button. Remembers it for
 * [CENTER_FORM_SCRIPT] and returns its width and the visible width as JSON; returns null while it's still the one
 * zoomed to last time, so running this again only acts when the page changed (and leaves the user's own zoom alone).
 */
internal const val FIND_FORM_SCRIPT = """
(function() {
  function visible(e) {
    var r = e.getBoundingClientRect(), s = getComputedStyle(e);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
  }
  var target = Array.from(document.querySelectorAll('[role=dialog],[aria-modal=true]')).filter(visible).pop();
  if (!target) {
    var password = Array.from(document.querySelectorAll('input[type=password]')).find(visible);
    target = password && (password.closest('form') || password.parentElement);
  }
  if (!target) target = Array.from(document.querySelectorAll('form')).find(visible);
  if (!target) target = Array.from(document.querySelectorAll('[role=button],button')).find(function(e) {
    return visible(e) && /continue|kontynuuj/i.test(e.innerText || '');
  });
  if (!target || target === window.__hydFocus) return null;
  window.__hydFocus = target;
  var r = target.getBoundingClientRect();
  var visibleWidth = window.visualViewport ? window.visualViewport.width : window.innerWidth;
  return JSON.stringify({ width: r.width, viewportWidth: visibleWidth });
})()
"""

internal const val CENTER_FORM_SCRIPT =
    "window.__hydFocus && window.__hydFocus.scrollIntoView({block: 'center', inline: 'center'});"

internal const val FOCUS_DELAY_MILLIS = 600L
internal const val FORM_WIDTH_SHARE = 0.9
internal const val MAX_ZOOM_STEP = 3f
/** How often the page is checked for a new thing to zoom to (a dialog closing, the next login step). */
internal const val REFOCUS_INTERVAL_MILLIS = 1_500L

/** Parses a `name=value; name2=value2` cookie header. */
internal fun parseCookieHeader(header: String?): Map<String, String> =
    header.orEmpty()
        .split(";")
        .mapNotNull { part ->
            val name = part.substringBefore("=", missingDelimiterValue = "").trim()
            if (name.isEmpty()) null else name to part.substringAfter("=").trim()
        }
        .toMap()

/** Reads the values an extract script returned (a JSON object of strings); anything else gives none. */
internal fun parseExtractedValues(json: String?): Map<String, String> {
    val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json ?: return emptyMap()) }.getOrNull()
        as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
    return obj.mapNotNull { (key, value) ->
        (value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
    }.toMap()
}
