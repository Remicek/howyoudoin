package com.devvalor.howyoudoing.networks.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shows [url] so the user can log in on the network's own website, then reports the
 * values of [cookieNames] once they are all set. Used for bridges whose login step is
 * "cookies" (e.g. mautrix-meta for Messenger).
 *
 * Cookies are read for [cookieUrl] (default [url]). [extractScript], if given, runs in the page on every poll and
 * returns a JSON object (as a string) of more values, e.g. a token from the page's storage; they count as cookies.
 */
@Composable
expect fun CookieCaptureWebView(
    url: String,
    userAgent: String?,
    cookieNames: Set<String>,
    onCookiesCaptured: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
    cookieUrl: String? = null,
    extractScript: String? = null,
)

internal const val COOKIE_POLL_INTERVAL_MILLIS = 1_000L

/**
 * Finds what the user has to act on on a login page (the form with a password field, another visible form, or a
 * "Continue" button), remembers it for [CENTER_FORM_SCRIPT] and returns its width and the viewport's as JSON.
 */
internal const val FIND_FORM_SCRIPT = """
(function() {
  function visible(e) { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  var password = Array.from(document.querySelectorAll('input[type=password]')).find(visible);
  var target = password && (password.closest('form') || password.parentElement);
  if (!target) target = Array.from(document.querySelectorAll('form')).find(visible);
  if (!target) target = Array.from(document.querySelectorAll('[role=button],button')).find(function(e) {
    return visible(e) && /continue|kontynuuj/i.test(e.innerText || '');
  });
  if (!target) return null;
  window.__hydFocus = target;
  var r = target.getBoundingClientRect();
  return JSON.stringify({ width: r.width, viewportWidth: window.innerWidth });
})()
"""

internal const val CENTER_FORM_SCRIPT =
    "window.__hydFocus && window.__hydFocus.scrollIntoView({block: 'center', inline: 'center'});"

internal const val FOCUS_DELAY_MILLIS = 600L
internal const val FORM_WIDTH_SHARE = 0.9
internal const val MAX_ZOOM_STEP = 3f

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
