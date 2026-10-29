package com.devvalor.howyoudoing.chat.domain

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

/**
 * Someone the user can write to on a network: a person a connected network has told the app about (its contacts, and
 * the people in the user's chats). [chatId] is the one-to-one chat with them, when there already is one.
 */
data class ChatContact(
    val id: String,
    val name: String,
    val network: ChatNetwork,
    val avatarUrl: String? = null,
    val chatId: String? = null
)
