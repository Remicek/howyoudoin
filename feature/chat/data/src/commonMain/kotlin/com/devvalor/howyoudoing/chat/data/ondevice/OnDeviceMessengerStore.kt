package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.chat.domain.Reaction
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
        val hasMoreBefore: Map<String, Boolean> = emptyMap(),
        /** Facebook thread key → the encrypted chat that replaced it. */
        val links: Map<String, Link> = emptyMap(),
        /** Encrypted thread key → timestamp to continue history paging from. */
        val historyCursors: Map<String, Long> = emptyMap(),
        /** Thread key → message ID → sender → emoji. */
        val reactions: Map<String, Map<String, Map<String, String>>> = emptyMap()
    )

    data class Link(val threadKey: String, val isGroup: Boolean, val lastActivityMs: Long)

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
        val status: MessageStatus = MessageStatus.SENT,
        val kind: String = "text"
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
        if (event.str("type") == "history") return resetEncryptedPaging()
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
                    when {
                        key.isEmpty() -> s
                        event.bool("isEncrypted") -> s.copy(
                            hasMoreBefore = s.hasMoreBefore + (key to event.bool("hasMoreBefore")),
                            historyCursors = s.historyCursors + (key to event.long("minTimestampMs"))
                        )
                        else -> s.copy(hasMoreBefore = s.hasMoreBefore + (key to event.bool("hasMoreBefore")))
                    }
                }
                "link" -> {
                    val fbKey = event.str("fbThreadKey")
                    val key = event.str("threadKey")
                    if (fbKey.isEmpty() || key.isEmpty()) s
                    else s.copy(links = s.links + (fbKey to Link(key, event.bool("isGroup"), event.long("lastActivityMs"))))
                }
                "reaction" -> {
                    val key = event.str("threadKey")
                    val target = event.str("targetId")
                    val sender = event.str("senderId")
                    if (key.isEmpty() || target.isEmpty() || sender.isEmpty()) s
                    else {
                        val byMessage = s.reactions[key].orEmpty()
                        val bySender = byMessage[target].orEmpty().let { current ->
                            val emoji = event.str("emoji")
                            if (emoji.isEmpty()) current - sender else current + (sender to emoji)
                        }
                        s.copy(reactions = s.reactions + (key to (byMessage + (target to bySender))))
                    }
                }
                "message" -> {
                    val message = StoredMessage(
                        id = event.str("id"), threadKey = event.str("threadKey"), otid = event.str("otid"),
                        senderId = event.str("senderId"), text = event.str("text"), timestampMs = event.long("timestampMs"),
                        isUnsent = event.bool("isUnsent"), kind = event.str("kind").ifEmpty { "text" }
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
        // A Facebook thread that moved to an encrypted chat is shown as that chat
        val replaced = s.links.filter { (fbKey, link) -> fbKey != link.threadKey }.keys
        // Encrypted chats announced only by a link have no thread row; build one from the link
        val linkedOnly = s.links.values
            .filter { it.threadKey !in s.threads }
            .associate { link ->
                link.threadKey to Thread(
                    key = link.threadKey, name = "", isGroup = link.isGroup, isEncrypted = true,
                    lastActivityMs = link.lastActivityMs, lastReadMs = link.lastActivityMs, snippet = "", snippetSender = ""
                )
            }
        return (s.threads + linkedOnly).values
            .filter { it.key !in replaced }
            .map { thread -> thread.toChat(s) }
    }

    fun thread(threadKey: String): Thread? = chatsByKey()[threadKey]

    private fun chatsByKey(): Map<String, Thread> {
        val s = _snapshot.value
        return s.threads + s.links.values.filter { it.threadKey !in s.threads }.associate {
            it.threadKey to Thread(it.threadKey, "", it.isGroup, true, it.lastActivityMs, it.lastActivityMs, "", "")
        }
    }

    fun historyCursor(threadKey: String): Long? = _snapshot.value.historyCursors[threadKey]

    /** After restoring history, encrypted chats can page again. */
    fun resetEncryptedPaging() = _snapshot.update { s ->
        val encrypted = chatsByKey().values.filter { it.isEncrypted }.map { it.key }.toSet()
        s.copy(hasMoreBefore = s.hasMoreBefore - encrypted, historyCursors = s.historyCursors - encrypted)
    }

    fun markNoMoreHistory(threadKey: String) = _snapshot.update { s ->
        s.copy(hasMoreBefore = s.hasMoreBefore + (threadKey to false))
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
        // Encrypted 1:1 chats are keyed by the other person's ID
        val others = (s.participants[key].orEmpty() + if (isEncrypted && !isGroup) setOf(key) else emptySet()) -
            setOfNotNull(s.selfId)
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
            isEncrypted = isEncrypted,
            isOnDevice = true
        )
    }

    private fun StoredMessage.toMessage(s: Snapshot): Message {
        val type = when {
            isUnsent -> MessageType.NOTICE
            kind == "image" -> MessageType.IMAGE
            kind == "video" -> MessageType.VIDEO
            kind == "audio" -> MessageType.AUDIO
            kind == "file" -> MessageType.FILE
            kind == "sticker" -> MessageType.STICKER
            kind == "text" -> MessageType.TEXT
            else -> MessageType.OTHER
        }
        // Media isn't downloaded on the device yet, so it's described in text
        val placeholder = when (type) {
            MessageType.IMAGE -> "📷 Photo"
            MessageType.VIDEO -> "🎬 Video"
            MessageType.AUDIO -> "🎤 Voice message"
            MessageType.FILE -> "📎 File"
            MessageType.STICKER -> "Sticker"
            MessageType.OTHER -> "Unsupported message"
            else -> ""
        }
        val reactions = s.reactions[threadKey]?.get(id).orEmpty()
            .entries.groupBy({ it.value }, { it.key })
            .map { (emoji, senders) ->
                Reaction(key = emoji, count = senders.size, ownReactionId = if (s.selfId in senders) "own-$emoji" else null)
            }
        return Message(
            id = id,
            chatId = CHAT_ID_PREFIX + threadKey,
            senderId = senderId,
            senderName = s.contacts[senderId]?.name.orEmpty(),
            body = when {
                isUnsent -> "Message unsent"
                placeholder.isNotEmpty() && text.isNotBlank() -> "$placeholder · $text"
                placeholder.isNotEmpty() -> placeholder
                else -> text
            },
            timestamp = timestampMs,
            isOutgoing = senderId.isNotEmpty() && senderId == s.selfId,
            // Shown as text bubbles until media is downloaded on the device
            type = if (type in MEDIA_TYPES) MessageType.TEXT else type,
            status = status,
            reactions = reactions
        )
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: 0L
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: false

    companion object {
        const val CHAT_ID_PREFIX = "fbdevice:"
        private val MEDIA_TYPES = setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.FILE, MessageType.STICKER)
        private const val CLOCK_SKEW_MS = 5_000L
    }
}
