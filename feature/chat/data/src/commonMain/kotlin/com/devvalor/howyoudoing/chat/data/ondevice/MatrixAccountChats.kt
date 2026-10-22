package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.chat.data.MatrixChatRepository
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The rooms of the Matrix account connected as a network (its own [MatrixChatRepository] and session). Chat IDs and
 * `mxc://` URLs get [PREFIX] so they can't be mistaken for the app's own server's.
 */
class MatrixAccountChats(private val matrix: MatrixChatRepository) {

    fun owns(id: String) = id.startsWith(PREFIX)

    private fun strip(id: String) = id.removePrefix(PREFIX)

    private fun String.prefixed() = if (startsWith("mxc://")) PREFIX + this else this

    private fun Message.prefixed() = copy(
        chatId = PREFIX + chatId,
        media = media?.let { it.copy(url = it.url.prefixed()) }
    )

    private fun Chat.prefixed() = copy(
        id = PREFIX + id,
        avatarUrl = avatarUrl?.prefixed(),
        lastMessage = lastMessage?.prefixed()
    )

    fun observeSyncState(): Flow<SyncState> = matrix.observeSyncState()
    fun observeChats(): Flow<List<Chat>> = matrix.observeChats().map { chats -> chats.map { it.prefixed() } }
    fun observeChat(chatId: String): Flow<Chat?> = matrix.observeChat(strip(chatId)).map { it?.prefixed() }
    fun observeMessages(chatId: String): Flow<List<Message>> =
        matrix.observeMessages(strip(chatId)).map { messages -> messages.map { it.prefixed() } }
    fun hasMoreHistory(chatId: String): Flow<Boolean> = matrix.hasMoreHistory(strip(chatId))

    suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote> = matrix.loadOlderMessages(strip(chatId))
    suspend fun sendMessage(chatId: String, body: String, replyToId: String?): EmptyResult<DataError.Remote> =
        matrix.sendMessage(strip(chatId), body, replyToId)
    suspend fun sendAttachment(chatId: String, attachment: Attachment, caption: String?): EmptyResult<DataError.Remote> =
        matrix.sendAttachment(strip(chatId), attachment, caption)
    suspend fun toggleReaction(chatId: String, messageId: String, key: String): EmptyResult<DataError.Remote> =
        matrix.toggleReaction(strip(chatId), messageId, key)
    suspend fun setTyping(chatId: String, isTyping: Boolean): EmptyResult<DataError.Remote> = matrix.setTyping(strip(chatId), isTyping)
    suspend fun editMessage(chatId: String, messageId: String, body: String): EmptyResult<DataError.Remote> =
        matrix.editMessage(strip(chatId), messageId, body)
    suspend fun deleteMessage(chatId: String, messageId: String): EmptyResult<DataError.Remote> =
        matrix.deleteMessage(strip(chatId), messageId)
    suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> = matrix.markAsRead(strip(chatId))
    suspend fun resolveMedia(url: String, thumbnail: Boolean): MediaRequest? = matrix.resolveMedia(strip(url), thumbnail)
    suspend fun downloadMedia(url: String): ByteArray? = matrix.downloadMedia(strip(url))
    suspend fun refresh() = matrix.refresh()

    companion object {
        const val PREFIX = "mxacct:"
    }
}
