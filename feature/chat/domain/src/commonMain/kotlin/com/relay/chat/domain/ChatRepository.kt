package com.relay.chat.domain

import com.relay.core.domain.util.DataError
import com.relay.core.domain.util.EmptyResult
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeSyncState(): Flow<SyncState>
    fun observeChats(): Flow<List<Chat>>
    fun observeChat(chatId: String): Flow<Chat?>

    /** Messages ordered oldest first. */
    fun observeMessages(chatId: String): Flow<List<Message>>

    suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote>
    fun hasMoreHistory(chatId: String): Flow<Boolean>

    suspend fun sendMessage(chatId: String, body: String): EmptyResult<DataError.Remote>
    suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote>
}
