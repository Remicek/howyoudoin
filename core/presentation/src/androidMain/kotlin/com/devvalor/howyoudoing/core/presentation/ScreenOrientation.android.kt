package com.devvalor.howyoudoing.core.presentation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun LockPortraitOnPhones() {
    val context = LocalContext.current
    // Phones are under 600dp at their narrowest; tablets (and foldables when unfolded) aren't
    val isPhone = LocalConfiguration.current.smallestScreenWidthDp < TABLET_MIN_WIDTH_DP
    DisposableEffect(context, isPhone) {
        val activity = context.findActivity()
        if (activity == null || !isPhone) return@DisposableEffect onDispose {}
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity.requestedOrientation = previous }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val TABLET_MIN_WIDTH_DP = 600
