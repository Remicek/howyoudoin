package com.devvalor.howyoudoing.chat.presentation.conversation

import com.devvalor.howyoudoing.chat.presentation.model.MessageUi
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

data class ConversationState(
    val chatName: String = "",
    val network: ChatNetwork? = null,
    val isGroup: Boolean = false,
    val isEncrypted: Boolean = false,
    val isOnDevice: Boolean = false,
    /** No notifications for this chat. */
    val isMuted: Boolean = false,
    val messages: List<MessageUi> = emptyList(),
    val draft: String = "",
    val isLoadingOlder: Boolean = false,
    val hasMoreHistory: Boolean = false,
    /** "typing…" / "Anna is typing…" shown under the chat name. */
    val typingText: String? = null,
    /** Message the composer replies to. */
    val replyingTo: MessageUi? = null,
    /** Message whose long-press actions (react, reply, copy) are open. */
    val actionsFor: MessageUi? = null,
    /** Image shown full screen. */
    val viewingImage: MessageUi? = null,
    val isSendingAttachment: Boolean = false
) {
    val canSend: Boolean get() = draft.isNotBlank()
}
