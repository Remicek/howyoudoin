package com.devvalor.howyoudoing.chat.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Which chat the user is looking at, so its messages don't also raise a notification. */
class OpenChatTracker {
    /** Chat open on screen (or null on other screens). */
    val openChatId = MutableStateFlow<String?>(null)

    /** False while the app is in the background (the open chat isn't seen then). */
    val isAppVisible = MutableStateFlow(false)

    fun isWatching(chatId: String): Boolean = isAppVisible.value && openChatId.value == chatId
}

/** Per-chat notification settings. */
interface NotificationPreferences {
    fun observeMutedChats(): Flow<Set<String>>
    suspend fun setMuted(chatId: String, muted: Boolean)
}
