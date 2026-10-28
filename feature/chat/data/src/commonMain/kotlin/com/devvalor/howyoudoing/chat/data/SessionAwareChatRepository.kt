package com.devvalor.howyoudoing.chat.data

import com.devvalor.howyoudoing.chat.data.demo.DemoChatRepository
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
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
    private val local: LocalOnlyChatRepository,
    private val demo: DemoChatRepository,
    private val sessionStorage: SessionStorage
) : ChatRepository {

    private fun <T> select(block: ChatRepository.() -> Flow<T>): Flow<T> =
        sessionStorage.observeAuthInfo()
            .map { it.kind() }
            .distinctUntilChanged()
            .flatMapLatest { kind -> repositoryFor(kind).block() }

    private suspend fun current(): ChatRepository =
        repositoryFor(sessionStorage.get().kind())

    private enum class Kind { SERVER, DEMO, LOCAL }

    private fun AuthInfo?.kind() = when {
        this?.isDemo == true -> Kind.DEMO
        this?.isLocal == true -> Kind.LOCAL
        else -> Kind.SERVER
    }

    private fun repositoryFor(kind: Kind) = when (kind) {
        Kind.SERVER -> matrix
        Kind.DEMO -> demo
        Kind.LOCAL -> local
    }

    override fun observeSyncState(): Flow<SyncState> = select { observeSyncState() }
    override fun observeChats(): Flow<List<Chat>> = select { observeChats() }
    override fun observeChat(chatId: String): Flow<Chat?> = select { observeChat(chatId) }
    override fun observeMessages(chatId: String): Flow<List<Message>> = select { observeMessages(chatId) }
    override fun hasMoreHistory(chatId: String): Flow<Boolean> = select { hasMoreHistory(chatId) }

    override suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote> =
        current().loadOlderMessages(chatId)

    override suspend fun sendMessage(chatId: String, body: String, replyToId: String?): EmptyResult<DataError.Remote> =
        current().sendMessage(chatId, body, replyToId)

    override suspend fun sendAttachment(
        chatId: String,
        attachment: Attachment,
        caption: String?
    ): EmptyResult<DataError.Remote> =
        current().sendAttachment(chatId, attachment, caption)

    override suspend fun toggleReaction(chatId: String, messageId: String, key: String): EmptyResult<DataError.Remote> =
        current().toggleReaction(chatId, messageId, key)

    override suspend fun setTyping(chatId: String, isTyping: Boolean): EmptyResult<DataError.Remote> =
        current().setTyping(chatId, isTyping)

    override suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean): MediaRequest? =
        current().resolveMedia(mxcUrl, thumbnail)

    override suspend fun refresh() = current().refresh()

    override suspend fun editMessage(chatId: String, messageId: String, body: String): EmptyResult<DataError.Remote> =
        current().editMessage(chatId, messageId, body)

    override suspend fun deleteMessage(chatId: String, messageId: String): EmptyResult<DataError.Remote> =
        current().deleteMessage(chatId, messageId)

    override suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> =
        current().markAsRead(chatId)

    override suspend fun acceptInvite(chatId: String): EmptyResult<DataError.Remote> = current().acceptInvite(chatId)

    override suspend fun declineInvite(chatId: String): EmptyResult<DataError.Remote> = current().declineInvite(chatId)

    override suspend fun retryMessage(chatId: String, messageId: String): EmptyResult<DataError.Remote> =
        current().retryMessage(chatId, messageId)

    override suspend fun discardFailedMessage(chatId: String, messageId: String) =
        current().discardFailedMessage(chatId, messageId)

    override suspend fun downloadMedia(mxcUrl: String): ByteArray? = current().downloadMedia(mxcUrl)
}
