package com.devvalor.howyoudoing.app.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.devvalor.howyoudoing.app.BuildConfig
import com.devvalor.howyoudoing.app.R

/**
 * A call through a link, on this side: the call server's own page (call-link/web), signed in by the link's
 * fragment. The page does the call (WebRTC in the WebView, end-to-end encrypted), so the app needs no second WebRTC
 * library next to the one Matrix calls use. It closes when the call ends.
 */
class LinkCallActivity : ComponentActivity() {

    private var webView: WebView? = null

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (!isGranted(Manifest.permission.RECORD_AUDIO)) {
                Toast.makeText(this, R.string.call_needs_mic, Toast.LENGTH_LONG).show()
                finish()
            } else {
                load()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val hostUrl = intent.getStringExtra(EXTRA_HOST_URL)
        // "Hang up" from the notification after the screen was already gone, or nothing to open
        if (hostUrl == null || intent.action == ACTION_HANG_UP) {
            LinkCallService.stop(this)
            finish()
            return
        }
        val wanted = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val missing = wanted.filterNot(::isGranted)
        if (missing.isEmpty()) load() else permissions.launch(missing.toTypedArray())
    }

    @SuppressLint("SetJavaScriptEnabled") // The call page is a script; only its own origin is loaded
    private fun load() {
        val hostUrl = intent.getStringExtra(EXTRA_HOST_URL) ?: return
        val origin = Uri.parse(hostUrl).let { "${it.scheme}://${it.authority}" }
        // Debug builds: the page can be inspected from chrome://inspect
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                // Anywhere else (a link on the page) opens in the browser, not in the call screen
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.toString().startsWith(origin)) return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (!request.origin.toString().startsWith(origin)) return request.deny()
                    val granted = request.resources.filter { resource ->
                        when (resource) {
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> isGranted(Manifest.permission.RECORD_AUDIO)
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> isGranted(Manifest.permission.CAMERA)
                            else -> false
                        }
                    }
                    if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
                }
            }
            addJavascriptInterface(CallBridge(), "HydCall")
            loadUrl(hostUrl)
        }
        webView = view
        setContentView(view)
        LinkCallService.start(this)
    }

    /** From the call notification (return to the call, or hang up), or a new call started while this one is open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when {
            intent.action == ACTION_HANG_UP -> finish()
            intent.getStringExtra(EXTRA_HOST_URL) != null -> {
                // The new call replaces this one: leaving the page ends it for the other person
                setIntent(intent)
                webView?.loadUrl(intent.getStringExtra(EXTRA_HOST_URL).orEmpty())
            }
        }
    }

    /** What the page tells the app; its methods run on a background thread. */
    private inner class CallBridge {
        @JavascriptInterface
        fun ended() {
            runOnUiThread { finish() }
        }
    }

    override fun onDestroy() {
        LinkCallService.stop(this)
        // Leaving the screen ends the call: the page goes, and with it the connection
        webView?.apply {
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val EXTRA_HOST_URL = "host_url"
        const val ACTION_HANG_UP = "com.devvalor.howyoudoing.LINK_CALL_HANG_UP"

        fun start(context: Context, hostUrl: String) {
            context.startActivity(
                Intent(context, LinkCallActivity::class.java)
                    .putExtra(EXTRA_HOST_URL, hostUrl)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
