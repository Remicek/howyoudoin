package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.chat.data.SessionAwareChatRepository
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map

/**
 * Server chats (Matrix / demo) plus Messenger chats connected on the device. Calls for [OnDeviceMessengerStore]
 * chat IDs go to the on-device client; the rest to the server repository.
 */
class OnDeviceChatRepository(
    private val server: SessionAwareChatRepository,
    private val messenger: OnDeviceMessenger,
    private val store: OnDeviceMessengerStore
) : ChatRepository {

    private fun String.threadKey(): String? =
        takeIf { it.startsWith(OnDeviceMessengerStore.CHAT_ID_PREFIX) }?.removePrefix(OnDeviceMessengerStore.CHAT_ID_PREFIX)

    override fun observeSyncState(): Flow<SyncState> = server.observeSyncState()

    override fun observeChats(): Flow<List<Chat>> =
        combine(server.observeChats(), store.snapshot) { serverChats, _ ->
            (serverChats + store.chats()).sortedByDescending { it.lastActivity }
        }

    override fun observeChat(chatId: String): Flow<Chat?> =
        if (chatId.threadKey() == null) server.observeChat(chatId)
        else store.snapshot.map { store.chats().firstOrNull { it.id == chatId } }

    override fun observeMessages(chatId: String): Flow<List<Message>> {
        val key = chatId.threadKey() ?: return server.observeMessages(chatId)
        return store.snapshot.map { store.messages(key) }
    }

    override suspend fun refresh() {
        messenger.connectIfLinked()
        server.refresh()
    }

    /**
     * Messenger answers a history request asynchronously over the socket, so this waits until older messages
     * (or a "no more history" range) arrive.
     */
    override suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote> {
        val key = chatId.threadKey() ?: return server.loadOlderMessages(chatId)
        val oldest = store.oldestMessage(key)
        if (!store.hasMoreBefore(key)) return Result.Success(Unit)
        val result = if (oldest == null) {
            // Nothing loaded for this chat yet: fetch the latest page
            messenger.requestOlder(key, Long.MAX_VALUE / 2, "")
        } else {
            messenger.requestOlder(key, oldest.timestampMs, oldest.id)
        }
        if (result is Result.Failure) return result
        withTimeoutOrNull(HISTORY_TIMEOUT_MILLIS) {
            store.snapshot.first { s ->
                s.hasMoreBefore[key] == false ||
                    s.messages[key].orEmpty().values.any { oldest == null || it.timestampMs < oldest.timestampMs }
            }
        }
        return Result.Success(Unit)
    }

    override fun hasMoreHistory(chatId: String): Flow<Boolean> {
        val key = chatId.threadKey() ?: return server.hasMoreHistory(chatId)
        return store.snapshot.map { store.hasMoreBefore(key) }.distinctUntilChanged()
    }

    override suspend fun sendMessage(chatId: String, body: String, replyToId: String?): EmptyResult<DataError.Remote> {
        val key = chatId.threadKey() ?: return server.sendMessage(chatId, body, replyToId)
        val pendingId = store.newPendingId()
        store.addPending(key, pendingId, body)
        return when (val result = messenger.sendText(key, body)) {
            is Result.Success -> {
                // The server's copy carries this ID and replaces the echo
                store.rekeyPending(pendingId, result.data)
                Result.Success(Unit)
            }
            is Result.Failure -> {
                store.failPending(pendingId)
                result
            }
        }
    }

    override suspend fun sendAttachment(chatId: String, attachment: Attachment, caption: String?): EmptyResult<DataError.Remote> =
        if (chatId.threadKey() == null) server.sendAttachment(chatId, attachment, caption) else Result.Failure(DataError.Remote.BAD_REQUEST)

    override suspend fun toggleReaction(chatId: String, messageId: String, key: String): EmptyResult<DataError.Remote> =
        if (chatId.threadKey() == null) server.toggleReaction(chatId, messageId, key) else Result.Failure(DataError.Remote.BAD_REQUEST)

    override suspend fun setTyping(chatId: String, isTyping: Boolean): EmptyResult<DataError.Remote> =
        if (chatId.threadKey() == null) server.setTyping(chatId, isTyping) else Result.Success(Unit)

    override suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> =
        if (chatId.threadKey() == null) server.markAsRead(chatId) else Result.Success(Unit)

    override suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean): MediaRequest? = server.resolveMedia(mxcUrl, thumbnail)

    override suspend fun downloadMedia(mxcUrl: String): ByteArray? = server.downloadMedia(mxcUrl)

    private companion object {
        const val HISTORY_TIMEOUT_MILLIS = 15_000L
    }
}
