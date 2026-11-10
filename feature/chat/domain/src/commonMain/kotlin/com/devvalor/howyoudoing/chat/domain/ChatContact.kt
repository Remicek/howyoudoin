package com.devvalor.howyoudoing.chat.domain

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

/**
 * Someone the user can write to on a network: a person a connected network has told the app about (its contacts, and
 * the people in the user's chats), or one a search of the network found. [chatId] is the one-to-one chat with them,
 * when there already is one; [handle] is how the network shows them (e.g. "@name").
 */
data class ChatContact(
    val id: String,
    val name: String,
    val network: ChatNetwork,
    val avatarUrl: String? = null,
    val chatId: String? = null,
    val handle: String? = null,
    /** Which of the network's accounts on the phone knows this person (0 for the first). */
    val accountSlot: Int = 0
)
