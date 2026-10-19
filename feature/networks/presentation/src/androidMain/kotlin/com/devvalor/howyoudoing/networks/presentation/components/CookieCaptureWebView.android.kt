package com.devvalor.howyoudoing.networks.presentation.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun CookieCaptureWebView(
    url: String,
    userAgent: String?,
    cookieNames: Set<String>,
    onCookiesCaptured: (Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val currentOnCookiesCaptured by rememberUpdatedState(onCookiesCaptured)

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Bridges ask for a desktop user agent (the session is reused with it), so the site
                // serves its desktop layout: zoom out to fit the screen and allow pinch-zoom.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                userAgent?.let { settings.userAgentString = it }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        // The desktop page shows small at overview zoom: zoom in on its login form
                        // (or the "Continue as …" prompt) once it has rendered
                        view.postDelayed({ zoomToForm(view) }, FOCUS_DELAY_MILLIS)
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
        modifier = modifier,
    )

    LaunchedEffect(url, cookieNames) {
        while (true) {
            val cookies = parseCookieHeader(CookieManager.getInstance().getCookie(url))
            if (cookieNames.isNotEmpty() && cookieNames.all { it in cookies }) {
                currentOnCookiesCaptured(cookies.filterKeys { it in cookieNames })
                break
            }
            delay(COOKIE_POLL_INTERVAL_MILLIS)
        }
    }
}

/**
 * Finds what the user has to act on (the form with a password field, another visible form, or a "Continue"
 * button), zooms so it spans most of the width and centers it. Pages without one are left as they are.
 */
private fun zoomToForm(webView: WebView) {
    webView.evaluateJavascript(FIND_FORM_SCRIPT) { result ->
        // evaluateJavascript hands back the script's result JSON-encoded: here a string holding JSON
        val json = result?.takeIf { it.startsWith("\"") }
            ?.let { runCatching { JSONObject(JSONArray("[$it]").getString(0)) }.getOrNull() }
            ?: return@evaluateJavascript
        val width = json.optDouble("width", 0.0)
        val viewportWidth = json.optDouble("viewportWidth", 0.0)
        if (width <= 0.0 || viewportWidth <= 0.0) return@evaluateJavascript
        val factor = (viewportWidth * FORM_WIDTH_SHARE / width).toFloat().coerceIn(1f, MAX_ZOOM_STEP)
        if (factor > 1.05f) webView.zoomBy(factor)
        webView.postDelayed({ webView.evaluateJavascript(CENTER_FORM_SCRIPT, null) }, FOCUS_DELAY_MILLIS)
    }
}

private const val FIND_FORM_SCRIPT = """
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

private const val CENTER_FORM_SCRIPT =
    "window.__hydFocus && window.__hydFocus.scrollIntoView({block: 'center', inline: 'center'});"

private const val FOCUS_DELAY_MILLIS = 600L
private const val FORM_WIDTH_SHARE = 0.9
private const val MAX_ZOOM_STEP = 3f
