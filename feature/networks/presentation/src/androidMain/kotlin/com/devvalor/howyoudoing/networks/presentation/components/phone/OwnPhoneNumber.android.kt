package com.devvalor.howyoudoing.networks.presentation.components.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Google's phone number picker first: no permission, the user picks the number. When it has nothing to offer (no
 * Google Play services, or "Phone number sharing" is off in the Google settings), the SIM's own number is read
 * instead, which asks for READ_PHONE_NUMBERS once.
 */
@Composable
internal actual fun rememberOwnPhoneNumberReader(onResult: (String?) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        currentOnResult(if (granted) readSimPhoneNumber(context) else null)
    }
    val readSim = remember(context, permissionLauncher) {
        {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                currentOnResult(readSimPhoneNumber(context))
            } else {
                permissionLauncher.launch(Manifest.permission.READ_PHONE_NUMBERS)
            }
        }
    }
    val pickerContract = ActivityResultContracts.StartIntentSenderForResult()
    val pickerLauncher = rememberLauncherForActivityResult(pickerContract) { result ->
        // Dismissed: leave the field as it is
        if (result.resultCode == Activity.RESULT_OK) {
            val picked = runCatching { Identity.getSignInClient(context).getPhoneNumberFromIntent(result.data) }
            currentOnResult(picked.getOrNull())
        }
    }
    val hasSim = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) }
    if (!hasSim) return null
    return remember(context, pickerLauncher, readSim) {
        {
            if (hasPlayServices(context)) {
                Identity.getSignInClient(context)
                    .getPhoneNumberHintIntent(GetPhoneNumberHintIntentRequest.builder().build())
                    .addOnSuccessListener { pickerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                    .addOnFailureListener { readSim() }
            } else {
                readSim()
            }
        }
    }
}

private fun hasPlayServices(context: Context): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

/**
 * The number the SIM stores for itself: the default subscription's first (calls, SMS, data). Only READ_PHONE_NUMBERS
 * is needed (listing every SIM would also need READ_PHONE_STATE). Null if none is stored, which is common.
 */
@SuppressLint("MissingPermission", "HardwareIds")
private fun readSimPhoneNumber(context: Context): String? = runCatching {
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
