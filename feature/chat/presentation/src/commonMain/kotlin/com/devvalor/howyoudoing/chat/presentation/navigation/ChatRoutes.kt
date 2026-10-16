package com.devvalor.howyoudoing.chat.presentation.navigation

import kotlinx.serialization.Serializable

sealed interface ChatRoutes {
    @Serializable data object Graph : ChatRoutes
    @Serializable data object Inbox : ChatRoutes
    @Serializable data class Conversation(val chatId: String) : ChatRoutes
}
