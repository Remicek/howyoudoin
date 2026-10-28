package com.devvalor.howyoudoing.chat.data

import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** "On this phone only" sessions have no server: every chat comes from the on-device connections. */
class LocalOnlyChatRepository : ChatRepository {
    override fun observeSyncState(): Flow<SyncState> = flowOf(SyncState.SYNCED)
    override fun observeChats(): Flow<List<Chat>> = flowOf(emptyList())
    override suspend fun refresh() = Unit
    override fun observeChat(chatId: String): Flow<Chat?> = flowOf(null)
    override fun observeMessages(chatId: String): Flow<List<Message>> = flowOf(emptyList())
    override suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote> = Result.Success(Unit)
    override fun hasMoreHistory(chatId: String): Flow<Boolean> = flowOf(false)
    override suspend fun sendMessage(chatId: String, body: String, replyToId: String?): EmptyResult<DataError.Remote> =
        noServer()

    override suspend fun sendAttachment(
        chatId: String,
        attachment: Attachment,
        caption: String?
    ): EmptyResult<DataError.Remote> = noServer()

    override suspend fun toggleReaction(chatId: String, messageId: String, key: String): EmptyResult<DataError.Remote> =
        noServer()

    override suspend fun setTyping(chatId: String, isTyping: Boolean): EmptyResult<DataError.Remote> =
        Result.Success(Unit)

    override suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> = Result.Success(Unit)
    override suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean): MediaRequest? = null
    override suspend fun downloadMedia(mxcUrl: String): ByteArray? = null

    private fun noServer(): EmptyResult<DataError.Remote> = Result.Failure(DataError.Remote.BAD_REQUEST)
}
