package com.devvalor.howyoudoing.networks.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlin.math.ceil
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSString
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.darwin.DISPATCH_TIME_NOW
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalForeignApi::class)
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

    UIKitView(
        factory = {
            NSURL.URLWithString(url)?.let { webView.loadRequest(NSURLRequest.requestWithURL(it)) }
            webView
        },
        modifier = modifier,
    )

    LaunchedEffect(url, cookieNames) {
        while (true) {
            val extracted = extractScript?.let { webView.evaluate(it) }
            val cookies = webView.cookiesFor(baseDomain) + parseExtractedValues(extracted)
            if (cookieNames.isNotEmpty() && cookieNames.all { it in cookies }) {
                currentOnCookiesCaptured(cookies.filterKeys { it in cookieNames })
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
                .filter { cookie -> cookie.domain.removePrefix(".").let { it.endsWith(baseDomain) || baseDomain.endsWith(it) } }
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
        val factor = (viewportWidth * FORM_WIDTH_SHARE / width).coerceIn(1.0, MAX_ZOOM_STEP.toDouble())
        val scrollView = webView.scrollView
        val scale = if (factor > 1.05) (scrollView.zoomScale * factor).coerceAtMost(scrollView.maximumZoomScale) else scrollView.zoomScale
        // iOS zooms in further on a focused field whose text shows under 16pt, pushing the form off screen:
        // size the form's fields' text so it shows at 16pt at the new zoom. Relayout resets the zoom, so this goes first.
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
    substringAfter(key, missingDelimiterValue = "").takeWhile { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()

private fun afterDelay(block: () -> Unit) {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, FOCUS_DELAY_MILLIS * 1_000_000), dispatch_get_main_queue(), block)
}
