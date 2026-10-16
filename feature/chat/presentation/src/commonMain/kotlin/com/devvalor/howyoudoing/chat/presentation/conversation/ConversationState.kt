package com.devvalor.howyoudoing.chat.presentation.conversation

import com.devvalor.howyoudoing.chat.presentation.model.MessageUi
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

data class ConversationState(
    val chatName: String = "",
    val network: ChatNetwork? = null,
    val isGroup: Boolean = false,
    val isEncrypted: Boolean = false,
    val messages: List<MessageUi> = emptyList(),
    val draft: String = "",
    val isLoadingOlder: Boolean = false,
    val hasMoreHistory: Boolean = false
) {
    val canSend: Boolean get() = draft.isNotBlank()
}
