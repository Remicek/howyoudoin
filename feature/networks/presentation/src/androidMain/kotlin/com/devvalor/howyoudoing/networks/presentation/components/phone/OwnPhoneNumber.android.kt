package com.devvalor.howyoudoing.networks.presentation.components.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
internal actual fun rememberOwnPhoneNumberReader(onResult: (String?) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        currentOnResult(if (granted) readOwnPhoneNumber(context) else null)
    }
    val hasSim = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) }
    if (!hasSim) return null
    return remember(context, launcher) {
        {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                currentOnResult(readOwnPhoneNumber(context))
            } else {
                launcher.launch(Manifest.permission.READ_PHONE_NUMBERS)
            }
        }
    }
}

/**
 * The number the SIM stores for itself: the default subscription's first (calls, SMS, data), else any. Only
 * READ_PHONE_NUMBERS is needed (listing every SIM would also need READ_PHONE_STATE). Null if none is stored.
 */
@SuppressLint("MissingPermission", "HardwareIds")
private fun readOwnPhoneNumber(context: Context): String? = runCatching {
    val numbers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        listOf(
            SubscriptionManager.getDefaultSubscriptionId(),
            SubscriptionManager.getDefaultVoiceSubscriptionId(),
            SubscriptionManager.getDefaultSmsSubscriptionId(),
            SubscriptionManager.getDefaultDataSubscriptionId(),
        ).filter { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            .distinct()
            .map { subscriptions.getPhoneNumber(it) }
    } else {
        @Suppress("DEPRECATION")
        listOf(context.getSystemService(TelephonyManager::class.java).line1Number)
    }
    numbers.firstOrNull { !it.isNullOrBlank() }
}.getOrNull()
