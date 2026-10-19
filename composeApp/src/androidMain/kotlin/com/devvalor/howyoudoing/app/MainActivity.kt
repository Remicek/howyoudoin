package com.devvalor.howyoudoing.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.devvalor.howyoudoing.app.notifications.AndroidNotificationPresenter
import com.devvalor.howyoudoing.app.notifications.NotificationRouter
import com.devvalor.howyoudoing.chat.domain.OpenChatTracker
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val openChatTracker: OpenChatTracker by inject()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleNotificationTap(intent)
        askForNotifications()
        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationTap(intent)
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
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
