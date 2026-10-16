package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Builds chats and messages from [OnDeviceMessenger] events. Chat IDs are [CHAT_ID_PREFIX] + thread key. */
@OptIn(ExperimentalTime::class)
class OnDeviceMessengerStore(
    private val messenger: OnDeviceMessenger,
    scope: CoroutineScope
) {
    data class Snapshot(
        val selfId: String? = null,
        val contacts: Map<String, Contact> = emptyMap(),
        val threads: Map<String, Thread> = emptyMap(),
        val participants: Map<String, Set<String>> = emptyMap(),
        /** Thread key → messages by ID. */
        val messages: Map<String, Map<String, StoredMessage>> = emptyMap(),
        /** Local echoes by offline threading ID until the server echoes them back. */
        val pending: Map<String, StoredMessage> = emptyMap(),
        /** Thread key → whether Messenger has messages before the oldest one we have (unknown means yes). */
        val hasMoreBefore: Map<String, Boolean> = emptyMap()
    )

    data class Contact(val id: String, val name: String, val firstName: String, val avatarUrl: String?)
    data class Thread(
        val key: String,
        val name: String,
        val isGroup: Boolean,
        val isEncrypted: Boolean,
        val lastActivityMs: Long,
        val lastReadMs: Long,
        val snippet: String,
        val snippetSender: String
    )

    data class StoredMessage(
        val id: String,
        val threadKey: String,
        val otid: String,
        val senderId: String,
        val text: String,
        val timestampMs: Long,
        val isUnsent: Boolean,
        val status: MessageStatus = MessageStatus.SENT
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    init {
        messenger.events.onEach(::apply).launchIn(scope)
        messenger.state.onEach { if (it == OnDeviceMessengerState.NotLinked) _snapshot.value = Snapshot() }.launchIn(scope)
    }

    fun addPending(threadKey: String, otid: String, text: String) = _snapshot.update { s ->
        s.copy(pending = s.pending + (otid to StoredMessage(
            id = "pending-$otid", threadKey = threadKey, otid = otid, senderId = s.selfId.orEmpty(), text = text,
            timestampMs = Clock.System.now().toEpochMilliseconds(), isUnsent = false, status = MessageStatus.SENDING
        )))
    }

    fun failPending(otid: String) = _snapshot.update { s ->
        s.pending[otid]?.let { s.copy(pending = s.pending + (otid to it.copy(status = MessageStatus.FAILED))) } ?: s
    }

    /** Moves a local echo to the offline threading ID the send returned; drops it if that message already arrived. */
    fun rekeyPending(localId: String, otid: String) = _snapshot.update { s ->
        val echo = s.pending[localId] ?: return@update s
        val arrived = s.messages[echo.threadKey].orEmpty().values.any { it.otid == otid }
        s.copy(pending = (s.pending - localId).let { if (arrived) it else it + (otid to echo.copy(otid = otid)) })
    }

    fun newPendingId(): String = "local-${Random.nextLong(1, Long.MAX_VALUE)}"

    private fun apply(json: String) {
        val event = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        _snapshot.update { s ->
            when (event.str("type")) {
                "self" -> s.copy(selfId = event.str("id"))
                "contact" -> event.str("id").takeIf { it.isNotEmpty() }?.let { id ->
                    s.copy(contacts = s.contacts + (id to Contact(id, event.str("name"), event.str("firstName"), event.str("avatarUrl").ifBlank { null })))
                } ?: s
                "thread" -> event.str("key").takeIf { it.isNotEmpty() }?.let { key ->
                    s.copy(threads = s.threads + (key to Thread(
                        key = key, name = event.str("name"), isGroup = event.bool("isGroup"), isEncrypted = event.bool("isEncrypted"),
                        lastActivityMs = event.long("lastActivityMs"), lastReadMs = event.long("lastReadMs"),
                        snippet = event.str("snippet"), snippetSender = event.str("snippetSender")
                    )))
                } ?: s
                "participant" -> {
                    val key = event.str("threadKey")
                    val contact = event.str("contactId")
                    if (key.isEmpty() || contact.isEmpty()) s
                    else s.copy(participants = s.participants + (key to (s.participants[key].orEmpty() + contact)))
                }
                "range" -> {
                    val key = event.str("threadKey")
                    if (key.isEmpty()) s else s.copy(hasMoreBefore = s.hasMoreBefore + (key to event.bool("hasMoreBefore")))
                }
                "message" -> {
                    val message = StoredMessage(
                        id = event.str("id"), threadKey = event.str("threadKey"), otid = event.str("otid"),
                        senderId = event.str("senderId"), text = event.str("text"), timestampMs = event.long("timestampMs"),
                        isUnsent = event.bool("isUnsent")
                    )
                    val thread = s.messages[message.threadKey].orEmpty()
                    s.copy(
                        messages = s.messages + (message.threadKey to (thread + (message.id to message))),
                        pending = s.pending - message.otid
                    )
                }
                else -> s
            }
        }
    }

    fun chats(): List<Chat> {
        val s = _snapshot.value
        return s.threads.values
            // Encrypted threads need the WhatsApp-protocol client, which isn't part of the on-device bridge yet
            .filterNot { it.isEncrypted }
            .map { thread -> thread.toChat(s) }
    }

    fun messages(threadKey: String): List<Message> {
        val s = _snapshot.value
        val stored = s.messages[threadKey].orEmpty().values + s.pending.values.filter { it.threadKey == threadKey }
        return stored.sortedBy { it.timestampMs }.map { it.toMessage(s) }
    }

    fun hasMoreBefore(threadKey: String): Boolean = _snapshot.value.hasMoreBefore[threadKey] ?: true

    /** The oldest message received from Messenger (not a local echo), used as the paging anchor. */
    fun oldestMessage(threadKey: String): StoredMessage? =
        _snapshot.value.messages[threadKey].orEmpty().values.minByOrNull { it.timestampMs }

    private fun Thread.toChat(s: Snapshot): Chat {
        val others = s.participants[key].orEmpty() - setOfNotNull(s.selfId)
        val name = name.ifBlank {
            others.mapNotNull { s.contacts[it] }.let { people ->
                if (isGroup) people.take(4).joinToString(", ") { it.firstName.ifBlank { it.name } } else people.firstOrNull()?.name.orEmpty()
            }
        }.ifBlank { if (isGroup) "Group chat" else "Messenger chat" }
        val latest = messages(key).lastOrNull()
        val lastMessage = latest?.takeIf { it.timestamp >= lastActivityMs - CLOCK_SKEW_MS } ?: Message(
            id = "snippet-$key", chatId = CHAT_ID_PREFIX + key, senderId = snippetSender,
            senderName = s.contacts[snippetSender]?.firstName.orEmpty(), body = snippet, timestamp = lastActivityMs,
            isOutgoing = snippetSender.isNotEmpty() && snippetSender == s.selfId
        )
        return Chat(
            id = CHAT_ID_PREFIX + key,
            name = name,
            network = ChatNetwork.MESSENGER,
            lastMessage = lastMessage,
            unreadCount = if (lastActivityMs > lastReadMs && lastMessage.isOutgoing.not()) 1 else 0,
            isGroup = isGroup,
            avatarUrl = null,
            isOnDevice = true
        )
    }

    private fun StoredMessage.toMessage(s: Snapshot) = Message(
        id = id,
        chatId = CHAT_ID_PREFIX + threadKey,
        senderId = senderId,
        senderName = s.contacts[senderId]?.name.orEmpty(),
        body = if (isUnsent) "Message unsent" else text,
        timestamp = timestampMs,
        isOutgoing = senderId.isNotEmpty() && senderId == s.selfId,
        type = if (isUnsent) MessageType.NOTICE else MessageType.TEXT,
        status = status
    )

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: 0L
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: false

    companion object {
        const val CHAT_ID_PREFIX = "fbdevice:"
        private const val CLOCK_SKEW_MS = 5_000L
    }
}
