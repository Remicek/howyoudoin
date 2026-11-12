package com.devvalor.howyoudoing.core.domain.util

/**
 * Debug lines for logcat. The app turns it off in release builds, so nothing it would say (HTTP errors with URLs,
 * the clients' state) reaches the phone's system log there. Use this instead of println: Kotlin inlines println
 * into System.out calls, which no ProGuard rule for it can strip.
 */
object AppLog {
    var enabled: Boolean = true

    inline fun d(message: () -> String) {
        if (enabled) println(message())
    }
}
