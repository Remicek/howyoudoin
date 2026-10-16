package com.relay.networks.presentation.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
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
                userAgent?.let { settings.userAgentString = it }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = WebViewClient()
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
