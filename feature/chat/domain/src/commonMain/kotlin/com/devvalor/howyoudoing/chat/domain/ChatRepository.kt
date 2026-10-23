package com.devvalor.howyoudoing.chat.domain

import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeSyncState(): Flow<SyncState>
    fun observeChats(): Flow<List<Chat>>
    /** Re-fetches all chats from the server (pull to refresh); returns when done. */
    suspend fun refresh()
    fun observeChat(chatId: String): Flow<Chat?>

    /** Messages ordered oldest first. */
    fun observeMessages(chatId: String): Flow<List<Message>>

    suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote>
    fun hasMoreHistory(chatId: String): Flow<Boolean>

    /** @param replyToId message being replied to, if any */
    suspend fun sendMessage(chatId: String, body: String, replyToId: String? = null): EmptyResult<DataError.Remote>
    suspend fun sendAttachment(chatId: String, attachment: Attachment, caption: String? = null): EmptyResult<DataError.Remote>

    /** Changes the text of one of the user's own messages. */
    suspend fun editMessage(chatId: String, messageId: String, body: String): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    /** Deletes one of the user's own messages for everyone. */
    suspend fun deleteMessage(chatId: String, messageId: String): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    /** Joins a chat the user was invited to ([Chat.isInvite]). */
    suspend fun acceptInvite(chatId: String): EmptyResult<DataError.Remote> = Result.Failure(DataError.Remote.BAD_REQUEST)

    /** Turns an invitation down. */
    suspend fun declineInvite(chatId: String): EmptyResult<DataError.Remote> = Result.Failure(DataError.Remote.BAD_REQUEST)

    /** Sends a message that failed to send again (see [Message.canRetry]). */
    suspend fun retryMessage(chatId: String, messageId: String): EmptyResult<DataError.Remote> =
        Result.Failure(DataError.Remote.BAD_REQUEST)

    /** Forgets a message that failed to send. */
    suspend fun discardFailedMessage(chatId: String, messageId: String) = Unit

    /** Adds the reaction, or removes it if the user already reacted with [key]. */
    suspend fun toggleReaction(chatId: String, messageId: String, key: String): EmptyResult<DataError.Remote>

    suspend fun setTyping(chatId: String, isTyping: Boolean): EmptyResult<DataError.Remote>
    suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote>

    /** HTTP URL + headers to load an `mxc://` media URI (e.g. for an image loader); null if not resolvable. */
    suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean = false): MediaRequest?

    /** Downloads the full file, e.g. to hand it to another app. */
    suspend fun downloadMedia(mxcUrl: String): ByteArray?
}

data class MediaRequest(
    val url: String,
    val headers: Map<String, String>
)
