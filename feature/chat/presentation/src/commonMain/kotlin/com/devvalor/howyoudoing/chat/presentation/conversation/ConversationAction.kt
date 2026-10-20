package com.devvalor.howyoudoing.chat.presentation.conversation

import com.devvalor.howyoudoing.chat.domain.Attachment

sealed interface ConversationAction {
    data class OnDraftChange(val draft: String) : ConversationAction
    data object OnSendClick : ConversationAction
    data object OnLoadOlder : ConversationAction
    data object OnBackClick : ConversationAction

    data class OnMessageLongPress(val messageId: String) : ConversationAction
    data object OnDismissActions : ConversationAction
    data class OnReactionClick(val messageId: String, val key: String) : ConversationAction
    data class OnReplyClick(val messageId: String) : ConversationAction
    data object OnCancelReply : ConversationAction
    data class OnEditClick(val messageId: String) : ConversationAction
    data object OnCancelEdit : ConversationAction
    data class OnDeleteClick(val messageId: String) : ConversationAction
    data object OnConfirmDelete : ConversationAction
    data object OnDismissDelete : ConversationAction
    data class OnCopyClick(val messageId: String) : ConversationAction

    data class OnAttachmentPicked(val attachment: Attachment) : ConversationAction
    /** A recorded voice message, sent as the network's voice note. */
    data class OnVoiceRecorded(val attachment: Attachment) : ConversationAction
    data class OnAttachmentError(val message: String) : ConversationAction
    data class OnMediaClick(val messageId: String) : ConversationAction
    data object OnCloseImage : ConversationAction
    data object OnToggleMute : ConversationAction

    data object OnSearchClick : ConversationAction
    data class OnSearchQueryChange(val query: String) : ConversationAction
    /** Moves to the next older match. */
    data object OnSearchOlder : ConversationAction
    data object OnSearchNewer : ConversationAction
    data object OnCloseSearch : ConversationAction
}
