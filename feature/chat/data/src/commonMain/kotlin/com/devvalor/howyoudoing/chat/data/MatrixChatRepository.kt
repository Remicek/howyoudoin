package com.devvalor.howyoudoing.chat.data

import com.devvalor.howyoudoing.chat.data.mappers.toChat
import com.devvalor.howyoudoing.chat.data.mappers.toMessages
import com.devvalor.howyoudoing.chat.data.mappers.withSenderNames
import com.devvalor.howyoudoing.chat.data.matrix.MatrixApi
import com.devvalor.howyoudoing.chat.data.matrix.MatrixDirectChats
import com.devvalor.howyoudoing.chat.data.matrix.MatrixInvite
import com.devvalor.howyoudoing.chat.data.matrix.MatrixSyncEngine
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.ChatContact
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
    private val engine: MatrixSyncEngine,
    api: MatrixApi
) : ChatRepository {

    private val directChats = MatrixDirectChats(api, engine)

    override fun observeSyncState(): Flow<SyncState> =
        engine.syncState.onStart { engine.ensureStarted() }

    override fun observeChats(): Flow<List<Chat>> =
        combine(engine.rooms, engine.invites, engine.session) { rooms, invites, session ->
            val userId = session?.userId ?: return@combine emptyList()
            (rooms.values.map { it.toChat(userId) } + invites.values.map { it.toChat() })
                .sortedByDescending { it.lastActivity }
        }
            .onStart { engine.ensureStarted() }
            .flowOn(Dispatchers.Default)

    override fun observeChat(chatId: String): Flow<Chat?> =
        combine(
            engine.rooms.map { it[chatId] }.distinctUntilChanged(),
            engine.invites.map { it[chatId] }.distinctUntilChanged(),
            engine.session
        ) { room, invite, session ->
            when {
                session == null -> null
                room != null -> room.toChat(session.userId)
                else -> invite?.toChat()
            }
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

    /** Whether an account is signed in (the Matrix account connected as a network may not be). */
    val hasSession: Boolean get() = engine.session.value != null

    override suspend fun searchPeople(
        network: ChatNetwork,
        query: String,
        accountSlot: Int
    ): Result<List<ChatContact>, DataError.Remote> {
        val found = if (network == ChatNetwork.MATRIX) {
            directChats.searchPeople(query)
        } else {
            Result.Failure(DataError.Remote.BAD_REQUEST)
        }
        return when (found) {
            is Result.Failure -> found
            is Result.Success -> Result.Success(
                found.data.map { person ->
                    ChatContact(
                        id = person.userId,
                        name = person.displayName ?: person.userId,
                        network = ChatNetwork.MATRIX,
                        avatarUrl = person.avatarUrl,
                        chatId = directChats.directRoomWith(person.userId),
                        handle = person.userId
                    )
                }
            )
        }
    }

    override suspend fun startChat(contact: ChatContact): Result<String, DataError.Remote> = when {
        contact.chatId != null -> Result.Success(contact.chatId.orEmpty())
        contact.network == ChatNetwork.MATRIX -> directChats.startDirectChat(contact.id)
        else -> Result.Failure(DataError.Remote.BAD_REQUEST)
    }

    override suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote> =
        engine.loadOlder(chatId)

    override suspend fun sendMessage(chatId: String, body: String, replyToId: String?): EmptyResult<DataError.Remote> =
        engine.sendText(chatId, body, replyToId)

    override suspend fun sendAttachment(
        chatId: String,
        attachment: Attachment,
        caption: String?
    ): EmptyResult<DataError.Remote> =
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
        engine.resolveMedia(mxcUrl, thumbnail)

    override suspend fun refresh() = engine.refresh()

    override suspend fun editMessage(chatId: String, messageId: String, body: String): EmptyResult<DataError.Remote> =
        engine.editMessage(chatId, messageId, body)

    override suspend fun deleteMessage(chatId: String, messageId: String): EmptyResult<DataError.Remote> =
        engine.deleteMessage(chatId, messageId)

    override suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> =
        engine.markAsRead(chatId)

    override suspend fun downloadMedia(mxcUrl: String): ByteArray? = engine.downloadMedia(mxcUrl)

    override suspend fun retryMessage(chatId: String, messageId: String): EmptyResult<DataError.Remote> =
        engine.retryFailed(chatId, messageId)

    override suspend fun discardFailedMessage(chatId: String, messageId: String) =
        engine.discardFailed(chatId, messageId)

    override suspend fun acceptInvite(chatId: String): EmptyResult<DataError.Remote> = engine.acceptInvite(chatId)

    override suspend fun declineInvite(chatId: String): EmptyResult<DataError.Remote> = engine.declineInvite(chatId)

    private fun MatrixInvite.toChat() = Chat(
        id = roomId,
        name = roomName ?: inviterName,
        network = ChatNetwork.MATRIX,
        lastMessage = Message(
            id = "invite-$roomId", chatId = roomId, senderId = inviterId, senderName = "",
            body = if (isDirect) "$inviterName wants to chat" else "$inviterName invited you", timestamp = timestamp,
            isOutgoing = false
        ),
        unreadCount = 1,
        isGroup = !isDirect,
        isInvite = true
    )
}
