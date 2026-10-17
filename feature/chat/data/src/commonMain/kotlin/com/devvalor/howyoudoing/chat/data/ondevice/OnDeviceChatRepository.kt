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
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException
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
    private val store: OnDeviceMessengerStore,
    private val httpClient: HttpClient
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
        val thread = store.thread(key)
        if (thread?.isEncrypted == true) return loadEncryptedHistory(key, thread.isGroup, oldest?.timestampMs)
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

    /** Encrypted history comes from the PIN-protected backup; without a restored backup there's nothing older. */
    private suspend fun loadEncryptedHistory(key: String, isGroup: Boolean, oldestTimestampMs: Long?): EmptyResult<DataError.Remote> {
        val before = store.historyCursor(key) ?: oldestTimestampMs ?: 0L
        if (messenger.fetchEncryptedHistory(key, isGroup, before) is Result.Failure) store.markNoMoreHistory(key)
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
        val thread = store.thread(key)
        return when (val result = messenger.sendText(key, body, thread?.isEncrypted == true, thread?.isGroup == true)) {
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

    override suspend fun toggleReaction(chatId: String, messageId: String, key: String): EmptyResult<DataError.Remote> {
        val threadKey = chatId.threadKey() ?: return server.toggleReaction(chatId, messageId, key)
        val thread = store.thread(threadKey)
        val target = store.messages(threadKey).firstOrNull { it.id == messageId }
        val isOwn = target?.reactions?.any { it.key == key && it.isOwn } == true
        return messenger.sendReaction(
            threadKey, messageId, if (isOwn) "" else key,
            isEncrypted = thread?.isEncrypted == true, isGroup = thread?.isGroup == true, messageSenderId = target?.senderId.orEmpty()
        )
    }

    override suspend fun setTyping(chatId: String, isTyping: Boolean): EmptyResult<DataError.Remote> {
        val key = chatId.threadKey() ?: return server.setTyping(chatId, isTyping)
        val thread = store.thread(key) ?: return Result.Success(Unit)
        return messenger.setTyping(key, thread.isEncrypted, thread.isGroup, isTyping)
    }

    override suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> {
        val key = chatId.threadKey() ?: return server.markAsRead(chatId)
        val thread = store.thread(key) ?: return Result.Success(Unit)
        val newest = store.messages(key).lastOrNull { !it.isOutgoing }
        return messenger.markRead(key, thread.isEncrypted, thread.isGroup, newest?.id.orEmpty(), newest?.senderId.orEmpty())
    }

    // On-device media are Messenger CDN URLs, signed and loadable without extra headers
    override suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean): MediaRequest? =
        if (mxcUrl.isHttp()) MediaRequest(mxcUrl, emptyMap()) else server.resolveMedia(mxcUrl, thumbnail)

    override suspend fun downloadMedia(mxcUrl: String): ByteArray? {
        if (!mxcUrl.isHttp()) return server.downloadMedia(mxcUrl)
        return try {
            httpClient.get(mxcUrl).readRawBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // Decrypted media of encrypted chats are files in the app's private storage (file://)
    private fun String.isHttp() = startsWith("https://") || startsWith("http://") || startsWith("file://")

    private companion object {
        const val HISTORY_TIMEOUT_MILLIS = 15_000L
    }
}
