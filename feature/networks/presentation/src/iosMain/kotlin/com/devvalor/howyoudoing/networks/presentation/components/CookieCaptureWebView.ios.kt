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
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
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
) {
    val currentOnCookiesCaptured by rememberUpdatedState(onCookiesCaptured)
    val baseDomain = remember(url) {
        NSURL.URLWithString(url)?.host.orEmpty().removePrefix("www.")
    }
    val webView = remember {
        WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = WKWebViewConfiguration()).apply {
            userAgent?.let { customUserAgent = it }
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
            val cookies = webView.cookiesFor(baseDomain)
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
