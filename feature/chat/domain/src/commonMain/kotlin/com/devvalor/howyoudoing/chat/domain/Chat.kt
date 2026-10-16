package com.devvalor.howyoudoing.chat.domain

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

data class Chat(
    val id: String,
    val name: String,
    val network: ChatNetwork,
    val lastMessage: Message?,
    val unreadCount: Int,
    val isGroup: Boolean,
    val avatarUrl: String? = null,
    /** End-to-end encrypted on the remote network (e.g. Messenger's encrypted chats). */
    val isEncrypted: Boolean = false
) {
    /** Sort key for the inbox, newest activity first. */
    val lastActivity: Long get() = lastMessage?.timestamp ?: 0L
}
