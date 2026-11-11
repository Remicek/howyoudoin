package com.devvalor.howyoudoing.core.domain.app

import kotlinx.coroutines.flow.Flow

/** What the app notifies about, from Settings → Notifications. Both are on until turned off. */
data class NotificationChoices(
    /** New messages in chats that aren't muted. */
    val messages: Boolean = true,
    /** Incoming and missed calls: voice and video calls in the networks' chats, and Matrix calls ringing. */
    val calls: Boolean = true,
)

interface NotificationSettings {
    fun observe(): Flow<NotificationChoices>

    suspend fun setMessages(enabled: Boolean)

    suspend fun setCalls(enabled: Boolean)
}

/** The phone's own notification settings for the app, where sound and vibration are chosen. */
interface SystemNotificationSettings {
    /** False where the phone has no such settings to open (iOS for now). */
    val isSupported: Boolean

    /** Whether the phone lets the app show notifications at all. */
    fun areEnabled(): Boolean

    fun openApp()

    /** The sound and vibration of one kind of notification. */
    fun open(kind: NotificationKind)
}

enum class NotificationKind { MESSAGES, CALLS }

object NoSystemNotificationSettings : SystemNotificationSettings {
    override val isSupported = false
    override fun areEnabled() = true
    override fun openApp() = Unit
    override fun open(kind: NotificationKind) = Unit
}
