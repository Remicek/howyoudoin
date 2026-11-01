package com.devvalor.howyoudoing.app.captcha

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.devvalor.howyoudoing.core.domain.ondevice.CaptchaChallenge

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun CaptchaWebView(challenge: CaptchaChallenge, onSolved: (String) -> Unit, modifier: Modifier) {
    val currentOnSolved by rememberUpdatedState(onSolved)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Runs remote hCaptcha JS next to a JS bridge: keep it off the device's files
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onSolved(token: String) {
                            Handler(Looper.getMainLooper()).post { currentOnSolved(token) }
                        }
                    },
                    "HydCaptcha"
                )
                // Loaded as the network's own page: hCaptcha only runs on the sites its key is made for
                loadDataWithBaseURL(challenge.pageUrl, captchaPage(challenge), "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}
