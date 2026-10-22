package com.devvalor.howyoudoing.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.devvalor.howyoudoing.app.notifications.ConnectionProblemNotifier
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.devvalor.howyoudoing.app.settings.CurrentActivity
import com.devvalor.howyoudoing.settings.domain.AppLockRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.devvalor.howyoudoing.app.notifications.AndroidNotificationPresenter
import com.devvalor.howyoudoing.app.notifications.NotificationRouter
import com.devvalor.howyoudoing.chat.domain.OpenChatTracker
import org.koin.android.ext.android.inject

// A FragmentActivity because the fingerprint prompt (BiometricPrompt) needs one
class MainActivity : FragmentActivity() {

    private val openChatTracker: OpenChatTracker by inject()
    private val appLock: AppLockRepository by inject()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CurrentActivity.activity = this
        handleNotificationTap(intent)
        askForNotifications()
        hideFromRecentsWhileLocked()
        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationTap(intent)
    }

    override fun onResume() {
        super.onResume()
        CurrentActivity.activity = this
    }

    /** With the app lock on, the recent-apps preview and screenshots don't show the chats. */
    private fun hideFromRecentsWhileLocked() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                appLock.observeSettings().map { it.isEnabled }.distinctUntilChanged().collect { locked ->
                    if (locked) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        openChatTracker.isAppVisible.value = true
    }

    override fun onStop() {
        openChatTracker.isAppVisible.value = false
        super.onStop()
    }

    private fun handleNotificationTap(intent: Intent?) {
        intent?.getStringExtra(AndroidNotificationPresenter.EXTRA_CHAT_ID)?.let { NotificationRouter.pendingChatId.value = it }
        if (intent?.getBooleanExtra(ConnectionProblemNotifier.EXTRA_OPEN_NETWORKS, false) == true) {
            NotificationRouter.pendingNetworks.value = true
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
