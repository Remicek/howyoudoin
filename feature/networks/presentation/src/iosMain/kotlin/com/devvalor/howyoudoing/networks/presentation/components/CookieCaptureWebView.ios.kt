package com.devvalor.howyoudoing.networks.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CookieCaptureWebView(
    url: String,
    userAgent: String?,
    cookieNames: Set<String>,
    onCookiesCaptured: (Map<String, String>) -> Unit,
    modifier: Modifier,
    extractScript: String?,
    waitForUrl: String?,
    allowedHosts: Set<String>,
) {
    val currentOnCookiesCaptured by rememberUpdatedState(onCookiesCaptured)
    val baseDomain = remember(url) {
        NSURL.URLWithString(url)?.host.orEmpty().removePrefix("www.")
    }
    // WKWebView holds its navigation delegate weakly
    val navigationDelegate = remember { ZoomToFormDelegate() }
    val webView = remember {
        WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = WKWebViewConfiguration()).apply {
            userAgent?.let { customUserAgent = it }
            this.navigationDelegate = navigationDelegate
        }
    }

    // Cookies left from an earlier login would be captured before the user logs in: start each login clean
    var isCleared by remember { mutableStateOf(false) }
    UIKitView(
        factory = {
            webView.configuration.websiteDataStore.removeDataOfTypes(
                WKWebsiteDataStore.allWebsiteDataTypes(),
                modifiedSince = NSDate.dateWithTimeIntervalSince1970(0.0)
            ) {
                isCleared = true
                // The login URL can come from the (untrusted) bridge server; only open it if it's an allowed host.
                // (iOS isn't shipped; a WKNavigationDelegate decidePolicy hook would be needed to also pin redirects.)
                if (isNavigationAllowed(url, allowedHosts)) {
                    NSURL.URLWithString(url)?.let { webView.loadRequest(NSURLRequest.requestWithURL(it)) }
                }
            }
            webView
        },
        modifier = modifier,
    )

    // Re-zoom when what to act on changes (the cookie notice closes, the page moves to the next login step)
    LaunchedEffect(isCleared) {
        if (!isCleared) return@LaunchedEffect
        while (true) {
            delay(REFOCUS_INTERVAL_MILLIS)
            zoomToForm(webView)
        }
    }

    LaunchedEffect(url, cookieNames, isCleared) {
        if (!isCleared) return@LaunchedEffect
        while (true) {
            val extracted = extractScript?.let { webView.evaluate(it) }
            val cookies = webView.cookiesFor(baseDomain) + parseExtractedValues(extracted)
            val isAtUrl = isAtCaptureUrl(waitForUrl, webView.URL?.absoluteString)
            if (cookieNames.isNotEmpty() && cookieNames.all { it in cookies } && isAtUrl) {
                currentOnCookiesCaptured(cookies)
                break
            }
            delay(COOKIE_POLL_INTERVAL_MILLIS)
        }
    }
}

private suspend fun WKWebView.cookiesFor(baseDomain: String): Map<String, String> =
    suspendCoroutine { continuation ->
        configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies ->
            val values = cookies.orEmpty()
                .filterIsInstance<NSHTTPCookie>()
                .filter { cookie ->
                    cookie.domain.removePrefix(".").let { it.endsWith(baseDomain) || baseDomain.endsWith(it) }
                }
                .associate { it.name to it.value }
            continuation.resume(values)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private suspend fun WKWebView.evaluate(script: String): String? = suspendCoroutine { continuation ->
    evaluateJavaScript(script) { result, _ ->
        continuation.resume((result as? String) ?: (result as? NSString)?.toString())
    }
}

/**
 * The desktop page shows small at overview zoom: once a page has loaded, zooms in on its login form (or the
 * "Continue as …" prompt) and centers it. Pages without one are left as they are.
 */
private class ZoomToFormDelegate : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        afterDelay { zoomToForm(webView) }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun zoomToForm(webView: WKWebView) {
    webView.evaluateJavaScript(FIND_FORM_SCRIPT) { result, _ ->
        val json = (result as? String) ?: (result as? NSString)?.toString() ?: return@evaluateJavaScript
        val width = json.numberAfter("\"width\":") ?: return@evaluateJavaScript
        val viewportWidth = json.numberAfter("\"viewportWidth\":") ?: return@evaluateJavaScript
        if (width <= 0.0 || viewportWidth <= 0.0) return@evaluateJavaScript
        val factor = (viewportWidth * FORM_WIDTH_SHARE / width).coerceIn(1.0 / MAX_ZOOM_STEP, MAX_ZOOM_STEP.toDouble())
        val scrollView = webView.scrollView
        val scale = if (abs(factor - 1.0) > ZOOM_TOLERANCE) {
            (scrollView.zoomScale * factor).coerceIn(scrollView.minimumZoomScale, scrollView.maximumZoomScale)
        } else scrollView.zoomScale
        // iOS zooms in further on a focused field whose text shows under 16pt, pushing the form off screen:
        // size the form's fields' text so it shows at 16pt at the new zoom. Relayout resets the zoom, so this goes
        // first.
        val fontSize = ceil(IOS_FOCUS_ZOOM_FONT_SIZE / scale).toInt()
        webView.evaluateJavaScript(
            "window.__hydFocus && window.__hydFocus.querySelectorAll('input, select, textarea')" +
                    ".forEach(function(e) { e.style.setProperty('font-size', '${fontSize}px', 'important'); });",
            completionHandler = null,
        )
        afterDelay {
            if (scale != scrollView.zoomScale) scrollView.setZoomScale(scale, animated = false)
            afterDelay { webView.evaluateJavaScript(CENTER_FORM_SCRIPT, completionHandler = null) }
        }
    }
}

private const val IOS_FOCUS_ZOOM_FONT_SIZE = 16.5

private fun String.numberAfter(key: String): Double? =
    substringAfter(key, missingDelimiterValue = "").takeWhile { it.isDigit() || it == '.' || it == '-' }
        .toDoubleOrNull()

private fun afterDelay(block: () -> Unit) {
    val delay = FOCUS_DELAY_MILLIS * NSEC_PER_MSEC.toLong()
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delay), dispatch_get_main_queue(), block)
}

/** Zoom changes smaller than this aren't worth the jump. */
private const val ZOOM_TOLERANCE = 0.05
