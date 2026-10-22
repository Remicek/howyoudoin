package com.devvalor.howyoudoing.networks.presentation.components

import android.annotation.SuppressLint
import android.view.ViewGroup
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
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun CookieCaptureWebView(
    url: String,
    userAgent: String?,
    cookieNames: Set<String>,
    onCookiesCaptured: (Map<String, String>) -> Unit,
    modifier: Modifier,
    extractScript: String?,
) {
    val currentOnCookiesCaptured by rememberUpdatedState(onCookiesCaptured)
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }

    AndroidView(
        factory = { context ->
            WebView(context).also { webViewHolder[0] = it }.apply {
                // With AndroidView's default WRAP_CONTENT height, WebView resolves CSS viewport units (vh) to 0,
                // and pages sized with them (Instagram's login and cookie dialog) collapse to nothing
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
        onRelease = {
            webViewHolder[0] = null
            it.destroy()
        },
        modifier = modifier,
    )

    LaunchedEffect(url, cookieNames) {
        while (true) {
            val extracted = extractScript?.let { script -> webViewHolder[0]?.let { evaluate(it, script) } }
            val cookies = parseCookieHeader(CookieManager.getInstance().getCookie(url)) + parseExtractedValues(extracted)
            if (cookieNames.isNotEmpty() && cookieNames.all { it in cookies }) {
                currentOnCookiesCaptured(cookies.filterKeys { it in cookieNames })
                break
            }
            delay(COOKIE_POLL_INTERVAL_MILLIS)
        }
    }
}

/** Runs [script] in the page and returns its result (a string), or null. */
private suspend fun evaluate(webView: WebView, script: String): String? = suspendCancellableCoroutine { continuation ->
    webView.evaluateJavascript(script) { result ->
        // The result comes JSON-encoded: a string result is a quoted JSON string
        val decoded = result?.takeIf { it.startsWith("\"") }?.let { runCatching { JSONArray("[$it]").getString(0) }.getOrNull() }
        if (continuation.isActive) continuation.resume(decoded)
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
