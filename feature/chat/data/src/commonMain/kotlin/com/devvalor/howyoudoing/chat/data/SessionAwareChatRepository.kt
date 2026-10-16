package com.devvalor.howyoudoing.chat.data

import com.devvalor.howyoudoing.chat.data.demo.DemoChatRepository
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.core.domain.auth.SessionStorage
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Routes to the demo data set for demo sessions and to Matrix for everything else. */
class SessionAwareChatRepository(
    private val matrix: MatrixChatRepository,
    private val demo: DemoChatRepository,
    private val sessionStorage: SessionStorage
) : ChatRepository {

    private fun <T> select(block: ChatRepository.() -> Flow<T>): Flow<T> =
        sessionStorage.observeAuthInfo()
            .map { it?.isDemo == true }
            .distinctUntilChanged()
            .flatMapLatest { isDemo -> if (isDemo) demo.block() else matrix.block() }

    private suspend fun current(): ChatRepository =
        if (sessionStorage.get()?.isDemo == true) demo else matrix

    override fun observeSyncState(): Flow<SyncState> = select { observeSyncState() }
    override fun observeChats(): Flow<List<Chat>> = select { observeChats() }
    override fun observeChat(chatId: String): Flow<Chat?> = select { observeChat(chatId) }
    override fun observeMessages(chatId: String): Flow<List<Message>> = select { observeMessages(chatId) }
    override fun hasMoreHistory(chatId: String): Flow<Boolean> = select { hasMoreHistory(chatId) }

    override suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote> =
        current().loadOlderMessages(chatId)

    override suspend fun sendMessage(chatId: String, body: String): EmptyResult<DataError.Remote> =
        current().sendMessage(chatId, body)

    override suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> =
        current().markAsRead(chatId)
}
