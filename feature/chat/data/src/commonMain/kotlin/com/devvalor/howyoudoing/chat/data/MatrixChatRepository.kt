package com.devvalor.howyoudoing.chat.data

import com.devvalor.howyoudoing.chat.data.mappers.toChat
import com.devvalor.howyoudoing.chat.data.mappers.toMessages
import com.devvalor.howyoudoing.chat.data.mappers.withSenderNames
import com.devvalor.howyoudoing.chat.data.matrix.MatrixSyncEngine
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

class MatrixChatRepository(
    private val engine: MatrixSyncEngine
) : ChatRepository {

    override fun observeSyncState(): Flow<SyncState> =
        engine.syncState.onStart { engine.ensureStarted() }

    override fun observeChats(): Flow<List<Chat>> =
        combine(engine.rooms, engine.session) { rooms, session ->
            val userId = session?.userId ?: return@combine emptyList()
            rooms.values
                .map { it.toChat(userId) }
                .sortedByDescending { it.lastActivity }
        }
            .onStart { engine.ensureStarted() }
            .flowOn(Dispatchers.Default)

    override fun observeChat(chatId: String): Flow<Chat?> =
        combine(engine.rooms.map { it[chatId] }.distinctUntilChanged(), engine.session) { room, session ->
            if (room == null || session == null) null else room.toChat(session.userId)
        }
            .onStart { engine.ensureStarted() }
            .flowOn(Dispatchers.Default)

    override fun observeMessages(chatId: String): Flow<List<Message>> =
        combine(engine.rooms.map { it[chatId] }.distinctUntilChanged(), engine.session) { room, session ->
            if (room == null || session == null) emptyList()
            else room.withSenderNames(room.toMessages(session.userId))
        }
            .onStart { engine.ensureStarted() }
            .flowOn(Dispatchers.Default)

    override fun hasMoreHistory(chatId: String): Flow<Boolean> =
        engine.rooms.map { it[chatId]?.hasMoreHistory ?: false }.distinctUntilChanged()

    override suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote> =
        engine.loadOlder(chatId)

    override suspend fun sendMessage(chatId: String, body: String, replyToId: String?): EmptyResult<DataError.Remote> =
        engine.sendText(chatId, body, replyToId)

    override suspend fun sendAttachment(chatId: String, attachment: Attachment, caption: String?): EmptyResult<DataError.Remote> =
        engine.sendAttachment(chatId, attachment, caption)

    override suspend fun toggleReaction(chatId: String, messageId: String, key: String): EmptyResult<DataError.Remote> {
        val session = engine.session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        val own = engine.rooms.value[chatId]?.toMessages(session.userId)
            ?.firstOrNull { it.id == messageId }?.reactions?.firstOrNull { it.key == key }?.ownReactionId
        return engine.toggleReaction(chatId, messageId, key, own)
    }

    override suspend fun setTyping(chatId: String, isTyping: Boolean): EmptyResult<DataError.Remote> =
        engine.setTyping(chatId, isTyping)

    override suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean): MediaRequest? =
        engine.mediaRequest(mxcUrl, thumbnail)

    override suspend fun refresh() = engine.refresh()

    override suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> =
        engine.markAsRead(chatId)

    override suspend fun downloadMedia(mxcUrl: String): ByteArray? = engine.downloadMedia(mxcUrl)
}
