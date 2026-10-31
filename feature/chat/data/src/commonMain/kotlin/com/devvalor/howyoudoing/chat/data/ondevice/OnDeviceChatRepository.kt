package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.chat.domain.ChatContact
import com.devvalor.howyoudoing.chat.data.SessionAwareChatRepository
import com.devvalor.howyoudoing.core.domain.media.MessageRef
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull

/** A network connected on the device: its native client and the store built from its events. */
class OnDeviceSource(val client: OnDeviceMessenger, val store: OnDeviceMessengerStore)

/**
 * Server chats (Matrix / demo) plus chats of networks connected on the device (Messenger, WhatsApp). Calls for an
 * on-device chat go to its source, recognized by the store's chat ID prefix; the rest to the server repository.
 */
class OnDeviceChatRepository(
    private val server: SessionAwareChatRepository,
    private val sources: List<OnDeviceSource>,
    private val httpClient: HttpClient,
    private val matrixAccount: MatrixAccountChats
) : ChatRepository {

    /** A chat on a phone connection; [key] is read each time, as a chat started in the app can move. */
    private data class Route(val source: OnDeviceSource, val startKey: String) {
        val client get() = source.client
        val store get() = source.store
        val key get() = source.store.snapshot.value.currentKey(startKey)
        val chatId get() = source.store.chatIdPrefix + key
    }

    private fun route(chatId: String): Route? = sources.firstNotNullOfOrNull { source ->
        chatId.takeIf { it.startsWith(source.store.chatIdPrefix) }
            ?.let { Route(source, it.removePrefix(source.store.chatIdPrefix)) }
    }

    /**
     * The server's sync state matters only while it has chats; with only on-device connections an unreachable
     * server isn't "offline". On-device clients connecting show as connecting.
     */
    override fun observeSyncState(): Flow<SyncState> {
        // Only the server chats actually shown (not the duplicates of networks connected on the phone)
        val serverUsed = visibleServerChats().map { it.isNotEmpty() }.distinctUntilChanged()
        val onDeviceConnecting = combine(sources.map { it.client.state }) { states ->
            states.any { it == OnDeviceMessengerState.Connecting }
        }
        return combine(server.observeSyncState(), serverUsed, onDeviceConnecting) { serverState, used, connecting ->
            when {
                used && serverState != SyncState.IDLE && serverState != SyncState.SYNCED -> serverState
                connecting -> SyncState.CONNECTING
                else -> SyncState.SYNCED
            }
        }.distinctUntilChanged()
    }

    /**
     * When a network is connected on this phone, its chats come from there; the same chats from the server bridge
     * would only be duplicates, so they're hidden.
     */
    override fun observeChats(): Flow<List<Chat>> =
        sources.fold(
            combine(
                visibleServerChats(),
                matrixAccount.observeChats()
            ) { server, account -> server + account }) { chats, source ->
            combine(chats, source.store.snapshot) { soFar, _ -> soFar + source.store.chats() }
        }.map { chats -> chats.sortedByDescending { it.lastActivity } }
            // Not an empty inbox while the last run's is still being read
            .onStart { sources.forEach { it.store.awaitLoaded() } }

    private fun visibleServerChats(): Flow<List<Chat>> {
        val onDeviceNetworks =
            combine(sources.map { source -> source.client.state.map { source.client.network to it } }) { states ->
                states.filter { (_, state) -> state != OnDeviceMessengerState.NotLinked }.mapTo(HashSet()) { it.first }
            }
        return combine(
            server.observeChats(),
            onDeviceNetworks
        ) { chats, networks -> chats.filterNot { it.network in networks } }
    }

    /** The server's contacts plus every connected network's, by network; a network connected here wins. */
    override fun observeContacts(): Flow<List<ChatContact>> =
        sources.fold(server.observeContacts()) { contacts, source ->
            combine(contacts, source.store.snapshot, source.client.state) { soFar, _, state ->
                if (state == OnDeviceMessengerState.NotLinked) {
                    soFar
                } else {
                    val network = source.client.network
                    soFar.filterNot { it.network == network } + source.store.contacts(network)
                }
            }
        }.map { contacts -> contacts.sortedBy { it.name.lowercase() } }

    override suspend fun startChat(contact: ChatContact): Result<String, DataError.Remote> {
        val existing = contact.chatId
        val source = sources.firstOrNull { it.client.network == contact.network && it.client.isSupported }
        val key = source?.client?.directChatKey(contact.id)
        return when {
            existing != null -> Result.Success(existing)
            source == null -> server.startChat(contact)
            key != null -> Result.Success(source.store.startDirectChat(key, contact.id, contact.name))
            // Networks whose chats have IDs of their own open the chat there first (Bluesky, X)
            else -> when (val started = source.client.startChatWith(contact.id)) {
                is Result.Failure -> started
                is Result.Success ->
                    Result.Success(source.store.startDirectChat(started.data, contact.id, contact.name))
            }
        }
    }

    override suspend fun searchPeople(
        network: ChatNetwork,
        query: String
    ): Result<List<ChatContact>, DataError.Remote> {
        val source = sources.firstOrNull { it.client.network == network && it.client.isSupported }
            ?: return server.searchPeople(network, query)
        return when (val found = source.client.searchPeople(query)) {
            is Result.Failure -> found
            is Result.Success -> {
                // Someone already written to opens that chat
                val known = source.store.contacts(network).associateBy { it.id }
                Result.Success(
                    found.data.map { person ->
                        ChatContact(
                            id = person.id,
                            name = person.name,
                            network = network,
                            avatarUrl = person.avatarUrl,
                            chatId = known[person.id]?.chatId,
                            handle = person.handle
                        )
                    }
                )
            }
        }
    }

    override fun observeChat(chatId: String): Flow<Chat?> {
        if (matrixAccount.owns(chatId)) return matrixAccount.observeChat(chatId)
        val route = route(chatId) ?: return server.observeChat(chatId)
        return route.store.snapshot.map { route.store.chats().firstOrNull { it.id == route.chatId } }
    }

    override fun observeMessages(chatId: String): Flow<List<Message>> {
        if (matrixAccount.owns(chatId)) return matrixAccount.observeMessages(chatId)
        val route = route(chatId) ?: return server.observeMessages(chatId)
        return route.store.snapshot.map { route.store.messages(route.key) }
    }

    override suspend fun refresh() {
        sources.forEach { it.client.connectIfLinked() }
        server.refresh()
        matrixAccount.refresh()
    }

    /**
     * Messenger answers a history request asynchronously over the socket, so this waits until older messages
     * (or a "no more history" range) arrive. WhatsApp only has what its history sync delivered.
     */
    override suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote> {
        if (matrixAccount.owns(chatId)) return matrixAccount.loadOlderMessages(chatId)
        val route = route(chatId) ?: return server.loadOlderMessages(chatId)
        if (!route.store.hasMoreBefore(route.key)) return Result.Success(Unit)
        val oldest = route.store.oldestMessage(route.key)
        val thread = route.store.thread(route.key)
        return when {
            route.client.network == ChatNetwork.WHATSAPP -> loadWhatsAppHistory(route, oldest)
            // Messenger's encrypted chats load history from its PIN-protected backup; other networks page as usual
            thread?.isEncrypted == true && route.client.network == ChatNetwork.MESSENGER ->
                loadEncryptedHistory(route, thread.isGroup, oldest?.timestampMs)

            else -> loadOlderPage(route, oldest)
        }
    }

    private suspend fun loadOlderPage(
        route: Route,
        oldest: OnDeviceMessengerStore.StoredMessage?
    ): EmptyResult<DataError.Remote> {
        val (key, store, client) = Triple(route.key, route.store, route.client)
        val result = if (oldest == null) {
            // Nothing loaded for this chat yet: fetch the latest page
            client.requestOlder(key, Long.MAX_VALUE / 2, "")
        } else {
            client.requestOlder(key, oldest.timestampMs, oldest.id)
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

    /**
     * WhatsApp history comes from the user's own phone (an on-demand history sync anchored at the oldest message we
     * have). Without any message there's no anchor; if nothing older arrives, the chat has no more history here.
     */
    private suspend fun loadWhatsAppHistory(
        route: Route,
        oldest: OnDeviceMessengerStore.StoredMessage?
    ): EmptyResult<DataError.Remote> {
        val (key, store) = route.key to route.store
        val arrived = oldest != null && run {
            val isOutgoing = oldest.senderId == store.snapshot.value.selfId
            val requested = route.client.requestOlder(key, oldest.timestampMs, oldest.id, isOutgoing)
            requested is Result.Success && withTimeoutOrNull(HISTORY_TIMEOUT_MILLIS) {
                store.snapshot.first { s ->
                    s.messages[key].orEmpty().values.any { it.timestampMs < oldest.timestampMs }
                }
            } != null
        }
        // Nothing older came (or there's nothing to page from): WhatsApp has no more history to give
        if (!arrived) store.markNoMoreHistory(key)
        return Result.Success(Unit)
    }

    /** Encrypted history comes from the PIN-protected backup; without a restored backup there's nothing older. */
    private suspend fun loadEncryptedHistory(
        route: Route,
        isGroup: Boolean,
        oldestTimestampMs: Long?
    ): EmptyResult<DataError.Remote> {
        val before = route.store.historyCursor(route.key) ?: oldestTimestampMs ?: 0L
        if (route.client.fetchEncryptedHistory(
                route.key,
                isGroup,
                before
            ) is Result.Failure
        ) route.store.markNoMoreHistory(route.key)
        return Result.Success(Unit)
    }

    override fun hasMoreHistory(chatId: String): Flow<Boolean> {
        if (matrixAccount.owns(chatId)) return matrixAccount.hasMoreHistory(chatId)
        val route = route(chatId) ?: return server.hasMoreHistory(chatId)
        return route.store.snapshot.map { route.store.hasMoreBefore(route.key) }.distinctUntilChanged()
    }

    override suspend fun sendMessage(chatId: String, body: String, replyToId: String?): EmptyResult<DataError.Remote> {
        if (matrixAccount.owns(chatId)) return matrixAccount.sendMessage(chatId, body, replyToId)
        val route = route(chatId) ?: return server.sendMessage(chatId, body, replyToId)
        val store = route.store
        val pendingId = store.newPendingId()
        val replyTo = replyToId?.let { store.message(route.key, it) }
        store.addPending(route.key, pendingId, body, replyTo?.id)
        val thread = store.thread(route.key)
        val isEncrypted = thread?.isEncrypted == true
        val isGroup = thread?.isGroup == true
        val sent = if (replyTo != null) {
            val quoted = MessageRef(replyTo.id, replyTo.senderId, replyTo.body)
            route.client.sendReply(route.key, body, isEncrypted, isGroup, quoted)
        } else {
            route.client.sendText(route.key, body, isEncrypted, isGroup)
        }
        return when (val result = sent) {
            is Result.Success -> {
                // The network's copy carries this ID and replaces the echo
                store.rekeyPending(pendingId, result.data)
                Result.Success(Unit)
            }

            is Result.Failure -> {
                store.failPending(pendingId)
                result
            }
        }
    }

    override suspend fun sendAttachment(
        chatId: String,
        attachment: Attachment,
        caption: String?
    ): EmptyResult<DataError.Remote> {
        if (matrixAccount.owns(chatId)) return matrixAccount.sendAttachment(chatId, attachment, caption)
        val route = route(chatId) ?: return server.sendAttachment(chatId, attachment, caption)
        val store = route.store
        val pendingId = store.newPendingId()
        store.addPending(
            route.key,
            pendingId,
            caption ?: if (attachment.isVoice) "🎤 Voice message" else attachment.fileName,
            canRetry = false
        )
        val thread = store.thread(route.key)
        val result = route.client.sendAttachment(
            route.key,
            attachment,
            caption,
            isEncrypted = thread?.isEncrypted == true,
            isGroup = thread?.isGroup == true
        )
        return when (result) {
            is Result.Success -> {
                store.rekeyPending(pendingId, result.data)
                Result.Success(Unit)
            }

            is Result.Failure -> {
                store.failPending(pendingId)
                result
            }
        }
    }

    // Only Matrix has invitations
    override suspend fun acceptInvite(chatId: String): EmptyResult<DataError.Remote> =
        if (matrixAccount.owns(chatId)) matrixAccount.acceptInvite(chatId) else server.acceptInvite(chatId)

    override suspend fun declineInvite(chatId: String): EmptyResult<DataError.Remote> =
        if (matrixAccount.owns(chatId)) matrixAccount.declineInvite(chatId) else server.declineInvite(chatId)

    override suspend fun retryMessage(chatId: String, messageId: String): EmptyResult<DataError.Remote> {
        if (matrixAccount.owns(chatId)) return matrixAccount.retryMessage(chatId, messageId)
        val route = route(chatId) ?: return server.retryMessage(chatId, messageId)
        val failed = route.store.failedMessage(messageId)?.takeIf { it.canRetry }
            ?: return Result.Failure(DataError.Remote.BAD_REQUEST)
        route.store.removePending(messageId)
        return sendMessage(chatId, failed.text, failed.replyToId)
    }

    override suspend fun discardFailedMessage(chatId: String, messageId: String) {
        if (matrixAccount.owns(chatId)) return matrixAccount.discardFailedMessage(chatId, messageId)
        val route = route(chatId) ?: return server.discardFailedMessage(chatId, messageId)
        route.store.removePending(messageId)
    }

    override suspend fun toggleReaction(chatId: String, messageId: String, key: String): EmptyResult<DataError.Remote> {
        if (matrixAccount.owns(chatId)) return matrixAccount.toggleReaction(chatId, messageId, key)
        val route = route(chatId) ?: return server.toggleReaction(chatId, messageId, key)
        val thread = route.store.thread(route.key)
        val target = route.store.messages(route.key).firstOrNull { it.id == messageId }
        val isOwn = target?.reactions?.any { it.key == key && it.isOwn } == true
        return route.client.sendReaction(
            route.key,
            MessageRef(messageId, target?.senderId.orEmpty()),
            if (isOwn) "" else key,
            isEncrypted = thread?.isEncrypted == true,
            isGroup = thread?.isGroup == true
        )
    }

    override suspend fun setTyping(chatId: String, isTyping: Boolean): EmptyResult<DataError.Remote> {
        if (matrixAccount.owns(chatId)) return matrixAccount.setTyping(chatId, isTyping)
        val route = route(chatId) ?: return server.setTyping(chatId, isTyping)
        val thread = route.store.thread(route.key) ?: return Result.Success(Unit)
        return route.client.setTyping(route.key, thread.isEncrypted, thread.isGroup, isTyping)
    }

    override suspend fun editMessage(chatId: String, messageId: String, body: String): EmptyResult<DataError.Remote> {
        if (matrixAccount.owns(chatId)) return matrixAccount.editMessage(chatId, messageId, body)
        val route = route(chatId) ?: return server.editMessage(chatId, messageId, body)
        val thread = route.store.thread(route.key)
        return route.client.editMessage(
            route.key,
            messageId,
            body,
            thread?.isEncrypted == true,
            thread?.isGroup == true
        )
    }

    override suspend fun deleteMessage(chatId: String, messageId: String): EmptyResult<DataError.Remote> {
        if (matrixAccount.owns(chatId)) return matrixAccount.deleteMessage(chatId, messageId)
        val route = route(chatId) ?: return server.deleteMessage(chatId, messageId)
        val thread = route.store.thread(route.key)
        return route.client.unsend(route.key, messageId, thread?.isEncrypted == true, thread?.isGroup == true)
    }

    override suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> {
        if (matrixAccount.owns(chatId)) return matrixAccount.markAsRead(chatId)
        val route = route(chatId) ?: return server.markAsRead(chatId)
        val thread = route.store.thread(route.key) ?: return Result.Success(Unit)
        val newest = route.store.messages(route.key).lastOrNull { !it.isOutgoing }
        return route.client.markRead(
            route.key,
            thread.isEncrypted,
            thread.isGroup,
            newest?.id.orEmpty(),
            newest?.senderId.orEmpty()
        )
    }

    // On-device media are CDN URLs (signed, no extra headers) or decrypted files in private storage
    override suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean): MediaRequest? = when {
        matrixAccount.owns(mxcUrl) -> matrixAccount.resolveMedia(mxcUrl, thumbnail)
        mxcUrl.isDirect() -> MediaRequest(mxcUrl, emptyMap())
        OnDeviceMessengerStore.parseOnRequestMediaUrl(mxcUrl) != null -> null
        else -> server.resolveMedia(mxcUrl, thumbnail)
    }

    // Each HTTP engine has its own network exceptions; a failed download just shows no media
    @Suppress("TooGenericExceptionCaught")
    override suspend fun downloadMedia(mxcUrl: String): ByteArray? {
        if (matrixAccount.owns(mxcUrl)) return matrixAccount.downloadMedia(mxcUrl)
        OnDeviceMessengerStore.parseOnRequestMediaUrl(mxcUrl)?.let { (chatId, messageId) ->
            val route = route(chatId) ?: return null
            val ref = route.store.mediaRef(route.key, messageId) ?: return null
            return (route.client.downloadMedia(route.key, messageId, ref) as? Result.Success)?.data
        }
        if (mxcUrl.startsWith("file://")) {
            val path = mxcUrl.removePrefix("file://")
            return sources.firstNotNullOfOrNull { it.client.readMediaFile(path) }
        }
        if (!mxcUrl.isDirect()) return server.downloadMedia(mxcUrl)
        return try {
            httpClient.get(mxcUrl).readRawBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("OnDeviceChats: media download failed: ${e.message}")
            null
        }
    }

    private fun String.isDirect() = startsWith("https://") || startsWith("http://") || startsWith("file://")

    private companion object {
        const val HISTORY_TIMEOUT_MILLIS = 15_000L
    }
}
