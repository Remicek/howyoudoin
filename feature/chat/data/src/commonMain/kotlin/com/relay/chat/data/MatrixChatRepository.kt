package com.relay.chat.data

import com.relay.chat.data.mappers.toChat
import com.relay.chat.data.mappers.toMessages
import com.relay.chat.data.mappers.withSenderNames
import com.relay.chat.data.matrix.MatrixSyncEngine
import com.relay.chat.domain.Chat
import com.relay.chat.domain.ChatRepository
import com.relay.chat.domain.Message
import com.relay.chat.domain.SyncState
import com.relay.core.domain.util.DataError
import com.relay.core.domain.util.EmptyResult
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

    override suspend fun sendMessage(chatId: String, body: String): EmptyResult<DataError.Remote> =
        engine.sendText(chatId, body)

    override suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> =
        engine.markAsRead(chatId)
}
