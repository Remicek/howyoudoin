package com.relay.chat.domain

import com.relay.core.domain.network.ChatNetwork

data class Chat(
    val id: String,
    val name: String,
    val network: ChatNetwork,
    val lastMessage: Message?,
    val unreadCount: Int,
    val isGroup: Boolean,
    val avatarUrl: String? = null
) {
    /** Sort key for the inbox, newest activity first. */
    val lastActivity: Long get() = lastMessage?.timestamp ?: 0L
}
