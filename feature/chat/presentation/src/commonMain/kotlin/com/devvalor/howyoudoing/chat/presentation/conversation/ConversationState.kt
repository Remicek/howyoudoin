package com.devvalor.howyoudoing.chat.presentation.conversation

import com.devvalor.howyoudoing.core.presentation.util.UiText
import com.devvalor.howyoudoing.chat.presentation.platform.PlayableVideo
import com.devvalor.howyoudoing.chat.presentation.model.MessageUi
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

data class ConversationState(
    val chatName: String = "",
    val avatarUrl: String? = null,
    val network: ChatNetwork? = null,
    val isGroup: Boolean = false,
    val isEncrypted: Boolean = false,
    val isOnDevice: Boolean = false,
    /** No notifications for this chat. */
    val isMuted: Boolean = false,
    /** A one-to-one Matrix chat: audio and video calls work here. */
    val canCall: Boolean = false,
    /** An invitation not answered yet: Accept / Decline instead of the composer. */
    val isInvite: Boolean = false,
    val isAnsweringInvite: Boolean = false,
    val messages: List<MessageUi> = emptyList(),
    val draft: String = "",
    val isLoadingOlder: Boolean = false,
    val hasMoreHistory: Boolean = false,
    /** "typing…" / "Anna is typing…" shown under the chat name. */
    val typingText: UiText? = null,
    /** Message the composer replies to. */
    val replyingTo: MessageUi? = null,
    /** One of our messages being edited in the composer. */
    val editing: MessageUi? = null,
    /** One of our messages waiting for the user to confirm deleting it for everyone. */
    val confirmingDelete: MessageUi? = null,
    /** Message whose long-press actions (react, reply, copy) are open. */
    val actionsFor: MessageUi? = null,
    /** Image shown full screen. */
    val viewingImage: MessageUi? = null,
    /** Video playing full screen in the app. */
    val playingVideo: PlayableVideo? = null,
    /** A video being downloaded to play. */
    val isLoadingVideo: Boolean = false,
    /** A message brought into view from a reply's quote, highlighted for a moment. */
    val highlightedMessageId: String? = null,
    val isSendingAttachment: Boolean = false,
    /** Searching the loaded messages when not null. */
    val searchQuery: String? = null,
    /** IDs of messages matching [searchQuery], newest first. */
    val searchMatches: List<String> = emptyList(),
    val searchIndex: Int = 0,
    /** No internet: the saved messages stay readable; sending only reminds that it's offline. */
    val isOffline: Boolean = false
) {
    val canSend: Boolean get() = draft.isNotBlank()
    val currentMatchId: String? get() = searchMatches.getOrNull(searchIndex)
}
