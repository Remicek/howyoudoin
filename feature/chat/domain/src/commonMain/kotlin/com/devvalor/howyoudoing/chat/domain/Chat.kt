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
    val isEncrypted: Boolean = false,
    /** Display names of people typing right now. */
    val typingNames: List<String> = emptyList(),
    /** Connected from the phone itself (experimental on-device bridge), not through the server. */
    val isOnDevice: Boolean = false,
    /** An invitation (Matrix) not accepted yet: the chat shows Accept / Decline instead of the composer. */
    val isInvite: Boolean = false
) {
    /** Sort key for the inbox, newest activity first. */
    val lastActivity: Long get() = lastMessage?.timestamp ?: 0L
}
