package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.chat.data.MatrixChatRepository
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.ChatContact
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The rooms of the Matrix account connected as a network (its own [MatrixChatRepository] and session). Chat IDs and
 * `mxc://` URLs get [PREFIX] so they can't be mistaken for the app's own server's.
 */
class MatrixAccountChats(private val matrix: MatrixChatRepository) : ChatRepository {

    fun owns(id: String) = id.startsWith(PREFIX)

    private fun strip(id: String) = id.removePrefix(PREFIX)

    private fun String.prefixed() = if (startsWith("mxc://")) PREFIX + this else this

    private fun Message.prefixed() = copy(
        chatId = PREFIX + chatId,
        media = media?.let { it.copy(url = it.url.prefixed(), thumbnailUrl = it.thumbnailUrl?.prefixed()) }
    )

    private fun Chat.prefixed() = copy(
        id = PREFIX + id,
        avatarUrl = avatarUrl?.prefixed(),
        lastMessage = lastMessage?.prefixed()
    )

    override fun observeSyncState(): Flow<SyncState> = matrix.observeSyncState()
    override fun observeChats(): Flow<List<Chat>> = matrix.observeChats().map { chats -> chats.map { it.prefixed() } }
    override fun observeChat(chatId: String): Flow<Chat?> = matrix.observeChat(strip(chatId)).map { it?.prefixed() }
    override fun observeMessages(chatId: String): Flow<List<Message>> =
        matrix.observeMessages(strip(chatId)).map { messages -> messages.map { it.prefixed() } }

    override fun hasMoreHistory(chatId: String): Flow<Boolean> = matrix.hasMoreHistory(strip(chatId))

    override suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote> =
        matrix.loadOlderMessages(strip(chatId))

    override suspend fun sendMessage(chatId: String, body: String, replyToId: String?): EmptyResult<DataError.Remote> =
        matrix.sendMessage(strip(chatId), body, replyToId)

    override suspend fun sendAttachment(
        chatId: String,
        attachment: Attachment,
        caption: String?
    ): EmptyResult<DataError.Remote> =
        matrix.sendAttachment(strip(chatId), attachment, caption)

    override suspend fun toggleReaction(chatId: String, messageId: String, key: String): EmptyResult<DataError.Remote> =
        matrix.toggleReaction(strip(chatId), messageId, key)

    override suspend fun setTyping(chatId: String, isTyping: Boolean): EmptyResult<DataError.Remote> =
        matrix.setTyping(strip(chatId), isTyping)

    override suspend fun editMessage(chatId: String, messageId: String, body: String): EmptyResult<DataError.Remote> =
        matrix.editMessage(strip(chatId), messageId, body)

    override suspend fun deleteMessage(chatId: String, messageId: String): EmptyResult<DataError.Remote> =
        matrix.deleteMessage(strip(chatId), messageId)

    override suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> = matrix.markAsRead(strip(chatId))
    override suspend fun acceptInvite(chatId: String): EmptyResult<DataError.Remote> =
        matrix.acceptInvite(strip(chatId))

    override suspend fun declineInvite(chatId: String): EmptyResult<DataError.Remote> =
        matrix.declineInvite(strip(chatId))

    override suspend fun retryMessage(chatId: String, messageId: String): EmptyResult<DataError.Remote> =
        matrix.retryMessage(strip(chatId), messageId)

    override suspend fun discardFailedMessage(chatId: String, messageId: String) =
        matrix.discardFailedMessage(strip(chatId), messageId)

    override suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean): MediaRequest? =
        matrix.resolveMedia(strip(mxcUrl), thumbnail)

    override suspend fun downloadMedia(mxcUrl: String): ByteArray? = matrix.downloadMedia(strip(mxcUrl))
    override suspend fun refresh() = matrix.refresh()

    /** Whether the Matrix account is connected (signed in). */
    val isConnected: Boolean get() = matrix.hasSession

    override suspend fun searchPeople(
        network: ChatNetwork,
        query: String,
        accountSlot: Int
    ): Result<List<ChatContact>, DataError.Remote> =
        when (val found = matrix.searchPeople(network, query)) {
            is Result.Failure -> found
            is Result.Success -> Result.Success(
                found.data.map { contact ->
                    contact.copy(
                        avatarUrl = contact.avatarUrl?.prefixed(),
                        chatId = contact.chatId?.let { PREFIX + it }
                    )
                }
            )
        }

    override suspend fun startChat(contact: ChatContact): Result<String, DataError.Remote> =
        when (val started = matrix.startChat(contact.copy(chatId = contact.chatId?.let(::strip)))) {
            is Result.Failure -> started
            is Result.Success -> Result.Success(PREFIX + started.data)
        }

    companion object {
        const val PREFIX = "mxacct:"
    }
}
