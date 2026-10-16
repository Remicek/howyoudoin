package com.devvalor.howyoudoing.networks.presentation.components

import android.content.Context
import android.content.pm.ApplicationInfo

/** Debug builds are marked debuggable; release builds aren't. */
class AndroidDeveloperOptions(
    private val context: Context
) : DeveloperOptions {
    override fun isEnabled(): Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
