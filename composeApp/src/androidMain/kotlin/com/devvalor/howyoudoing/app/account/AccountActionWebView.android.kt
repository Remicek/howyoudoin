package com.devvalor.howyoudoing.app.account

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.devvalor.howyoudoing.core.domain.ondevice.AccountAction
import kotlinx.coroutines.delay

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun AccountActionWebView(
    action: AccountAction,
    allowedHosts: Set<String>,
    onCookies: (Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val currentOnCookies by rememberUpdatedState(onCookies)
    val cookieManager = CookieManager.getInstance()
    LaunchedEffect(action) {
        while (true) {
            delay(COOKIE_POLL_MILLIS)
            currentOnCookies(parseCookies(cookieManager.getCookie(action.url)))
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // Signed in as the connected account, so what's accepted here counts for it
            cookieDomain(action.url)?.let { domain ->
                action.cookies.forEach { (name, value) ->
                    cookieManager.setCookie("https://$domain", "$name=$value; Domain=.$domain; Path=/; Secure")
                }
                cookieManager.flush()
            }
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // As the desktop browser the login was made in: Facebook refuses its pages to a session that
                // suddenly shows up as a phone browser
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                val chrome = Regex("Chrome/[0-9]+").find(settings.userAgentString)?.value ?: "Chrome/141"
                settings.userAgentString = DESKTOP_USER_AGENT.replace("Chrome/141", chrome)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !isAllowed(request.url.toString(), allowedHosts)
                }
                loadUrl(action.url)
            }
        },
        onRelease = { it.destroy() },
    )
}
