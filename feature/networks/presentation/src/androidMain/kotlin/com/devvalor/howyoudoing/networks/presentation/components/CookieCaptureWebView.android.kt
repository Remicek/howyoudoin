package com.devvalor.howyoudoing.networks.presentation.components

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun CookieCaptureWebView(
    url: String,
    userAgent: String?,
    cookieNames: Set<String>,
    onCookiesCaptured: (Map<String, String>) -> Unit,
    modifier: Modifier,
    extractScript: String?,
    waitForUrl: String?,
) {
    val currentOnCookiesCaptured by rememberUpdatedState(onCookiesCaptured)
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    // Cookies left from an earlier login (e.g. one that has since expired) would be captured before the user logs
    // in, so each login starts from a clean slate; the app keeps its own copy of every login, not the web view
    var isCleared by remember { mutableStateOf(false) }

    AndroidView(
        factory = { context ->
            WebView(context).also { webViewHolder[0] = it }.apply {
                // With AndroidView's default WRAP_CONTENT height, WebView resolves CSS viewport units (vh) to 0,
                // and pages sized with them (Instagram's login and cookie dialog) collapse to nothing
                layoutParams =
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Bridges ask for a desktop user agent (the session is reused with it), so the site
                // serves its desktop layout: zoom out to fit the screen and allow pinch-zoom.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                userAgent?.let { settings.userAgentString = withWebViewChromeVersion(it, settings.userAgentString) }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    // Links only an app can open (slack://…) would end on an error page
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url.toString()
                        if (target.startsWith("https://") || target.startsWith("http://")) return false
                        webFallbackFor(target)?.let(view::loadUrl)
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // The desktop page shows small at overview zoom: zoom in on its login form
                        // (or the "Continue as …" prompt) once it has rendered
                        view.postDelayed({ zoomToForm(view) }, FOCUS_DELAY_MILLIS)
                    }
                }
                // A fresh login: an earlier one's page storage would hand over its stale token (Slack keeps it in
                // localStorage), which then doesn't match the new session cookie
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    isCleared = true
                    loadUrl(url)
                }
            }
        },
        onRelease = {
            webViewHolder[0] = null
            it.destroy()
        },
        modifier = modifier,
    )

    // Re-zoom when what to act on changes (the cookie notice closes, the page moves to the next login step)
    LaunchedEffect(isCleared) {
        if (!isCleared) return@LaunchedEffect
        while (true) {
            delay(REFOCUS_INTERVAL_MILLIS)
            webViewHolder[0]?.let(::zoomToForm)
        }
    }

    LaunchedEffect(url, cookieNames, isCleared) {
        if (!isCleared) return@LaunchedEffect
        while (true) {
            val extracted = extractScript?.let { script -> webViewHolder[0]?.let { evaluate(it, script) } }
            val cookies =
                parseCookieHeader(CookieManager.getInstance().getCookie(url)) + parseExtractedValues(extracted)
            val isAtUrl = isAtCaptureUrl(waitForUrl, webViewHolder[0]?.url)
            if (cookieNames.isNotEmpty() && cookieNames.all { it in cookies } && isAtUrl) {
                currentOnCookiesCaptured(cookies)
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
        val decoded =
            result?.takeIf { it.startsWith("\"") }?.let { runCatching { JSONArray("[$it]").getString(0) }.getOrNull() }
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
        // Relative to the current zoom: in to fit a small form, out to fit a wide dialog
        val factor = (viewportWidth * FORM_WIDTH_SHARE / width).toFloat().coerceIn(1f / MAX_ZOOM_STEP, MAX_ZOOM_STEP)
        if (abs(factor - 1f) > ZOOM_TOLERANCE) webView.zoomBy(factor)
        webView.postDelayed({ webView.evaluateJavascript(CENTER_FORM_SCRIPT, null) }, FOCUS_DELAY_MILLIS)
    }
}

/**
 * A requested (desktop) user agent with the Chrome version of this phone's WebView: a hard-coded version gets old,
 * and sites like Slack then say the browser won't be supported.
 */
private fun withWebViewChromeVersion(userAgent: String, webViewUserAgent: String): String {
    val chrome = Regex("Chrome/[0-9.]+")
    val actual = chrome.find(webViewUserAgent)?.value ?: return userAgent
    // Desktop Chrome reports only its major version ("Chrome/155.0.0.0")
    val major = actual.substringAfter('/').substringBefore('.')
    return userAgent.replace(chrome, "Chrome/$major.0.0.0")
}

/** Zoom changes smaller than this aren't worth the jump. */
private const val ZOOM_TOLERANCE = 0.05f
