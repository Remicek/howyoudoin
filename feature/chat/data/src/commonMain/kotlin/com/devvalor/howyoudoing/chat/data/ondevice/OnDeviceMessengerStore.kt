package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.MediaInfo
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.chat.domain.Reaction
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.runningFold
import kotlinx.serialization.Serializable
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

/** Keeps the on-device inbox between launches (a file in the app's private, non-backed-up storage). */
interface OnDeviceInboxCache {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/** Builds chats and messages from [OnDeviceMessenger] events. Chat IDs are [chatIdPrefix] + thread key. */
@OptIn(ExperimentalTime::class, FlowPreview::class)
class OnDeviceMessengerStore(
    private val messenger: OnDeviceMessenger,
    private val cache: OnDeviceInboxCache,
    scope: CoroutineScope,
    val chatIdPrefix: String = MESSENGER_CHAT_ID_PREFIX
) {
    @Serializable
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
        val reactions: Map<String, Map<String, Map<String, String>>> = emptyMap(),
        /** Message ID → its attachment (the first one; albums show their first photo). */
        val attachments: Map<String, StoredAttachment> = emptyMap(),
        /** Thread key → people typing. */
        val typing: Map<String, Set<String>> = emptyMap(),
        /** Thread key → contact → read up to (ms). */
        val readWatermarks: Map<String, Map<String, Long>> = emptyMap()
    )

    @Serializable
    data class Link(val threadKey: String, val isGroup: Boolean, val lastActivityMs: Long)

    @Serializable
    data class StoredAttachment(
        val kind: String,
        val url: String,
        val mimeType: String?,
        val fileName: String?,
        val width: Int?,
        val height: Int?
    ) {
        val media: MediaInfo get() = MediaInfo(url, mimeType, fileName, sizeBytes = null, width, height, durationMillis = null)
    }

    @Serializable
    data class Contact(val id: String, val name: String, val firstName: String, val avatarUrl: String?)
    @Serializable
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

    @Serializable
    data class StoredMessage(
        val id: String,
        val threadKey: String,
        val otid: String,
        val senderId: String,
        val text: String,
        val timestampMs: Long,
        val isUnsent: Boolean,
        val status: MessageStatus = MessageStatus.SENT,
        val kind: String = "text",
        val isEncrypted: Boolean = false
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    init {
        // The inbox from the last run, so chats known only from live messages survive restarts
        cache.read()?.let { saved ->
            runCatching { cacheJson.decodeFromString<Snapshot>(saved) }.getOrNull()?.let { _snapshot.value = it }
        }
        messenger.events.onEach(::apply).launchIn(scope)
        // Forget the inbox when the account is disconnected (not at launch, where the state also starts unlinked)
        messenger.state
            .runningFold(null as OnDeviceMessengerState? to (null as OnDeviceMessengerState?)) { (_, previous), current -> previous to current }
            .onEach { (previous, current) ->
                if (previous != null && previous != OnDeviceMessengerState.NotLinked && current == OnDeviceMessengerState.NotLinked) {
                    _snapshot.value = Snapshot()
                    cache.clear()
                }
            }
            .launchIn(scope)
        _snapshot
            .drop(1)
            .debounce(CACHE_WRITE_DELAY_MILLIS)
            .onEach { snapshot ->
                // Typing and unsent echoes only make sense while the app runs
                runCatching { cache.write(cacheJson.encodeToString(snapshot.copy(typing = emptyMap(), pending = emptyMap()))) }
            }
            .launchIn(scope)
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
                // Group names from WhatsApp's group list carry no activity; don't let them reset it
                "thread" if event.bool("nameOnly") -> event.str("key").takeIf { it.isNotEmpty() }?.let { key ->
                    val existing = s.threads[key]
                    s.copy(threads = s.threads + (key to (existing?.copy(name = event.str("name").ifBlank { existing.name }) ?: Thread(
                        key = key, name = event.str("name"), isGroup = event.bool("isGroup"), isEncrypted = event.bool("isEncrypted"),
                        lastActivityMs = 0L, lastReadMs = 0L, snippet = "", snippetSender = ""
                    ))))
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
                "attachment" -> {
                    val messageId = event.str("messageId")
                    if (messageId.isEmpty() || messageId in s.attachments) s
                    else s.copy(attachments = s.attachments + (messageId to StoredAttachment(
                        kind = event.str("kind"),
                        url = event.str("url"),
                        mimeType = event.str("mimeType").ifBlank { null },
                        fileName = event.str("fileName").ifBlank { null },
                        width = event.long("width").toInt().takeIf { it > 0 },
                        height = event.long("height").toInt().takeIf { it > 0 }
                    )))
                }
                "typing" -> {
                    val key = event.str("threadKey")
                    val sender = event.str("senderId")
                    if (key.isEmpty() || sender.isEmpty() || sender == s.selfId) s
                    else {
                        val current = s.typing[key].orEmpty()
                        s.copy(typing = s.typing + (key to if (event.bool("isTyping")) current + sender else current - sender))
                    }
                }
                "read" -> {
                    val key = event.str("threadKey")
                    val contact = event.str("contactId")
                    if (key.isEmpty() || contact.isEmpty()) s
                    else {
                        val current = s.readWatermarks[key].orEmpty()
                        val watermark = maxOf(current[contact] ?: 0L, event.long("watermarkMs"))
                        s.copy(readWatermarks = s.readWatermarks + (key to (current + (contact to watermark))))
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
                        isUnsent = event.bool("isUnsent"), kind = event.str("kind").ifEmpty { "text" },
                        isEncrypted = event.bool("isEncrypted")
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
        return allThreads(s).values
            .filter { it.key !in replaced }
            .map { thread -> thread.toChat(s) }
    }

    fun thread(threadKey: String): Thread? = allThreads(_snapshot.value)[threadKey]

    /**
     * Thread rows, plus encrypted chats known only from a link (no thread row) or only from live messages
     * (a chat that started after connecting).
     */
    private fun allThreads(s: Snapshot): Map<String, Thread> {
        val fromLinks = s.links.values.filter { it.threadKey !in s.threads }.associate { link ->
            link.threadKey to Thread(link.threadKey, "", link.isGroup, true, link.lastActivityMs, link.lastActivityMs, "", "")
        }
        val fromMessages = s.messages
            .filterKeys { it !in s.threads && it !in fromLinks }
            .mapNotNull { (key, messages) ->
                val newest = messages.values.maxByOrNull { it.timestampMs } ?: return@mapNotNull null
                if (!newest.isEncrypted) return@mapNotNull null
                key to Thread(key, "", false, true, newest.timestampMs, 0L, "", "")
            }
            .toMap()
        return s.threads + fromLinks + fromMessages
    }

    fun historyCursor(threadKey: String): Long? = _snapshot.value.historyCursors[threadKey]

    /** After restoring history, encrypted chats can page again. */
    fun resetEncryptedPaging() = _snapshot.update { s ->
        val encrypted = allThreads(s).values.filter { it.isEncrypted }.map { it.key }.toSet()
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
            id = "snippet-$key", chatId = chatIdPrefix + key, senderId = snippetSender,
            senderName = s.contacts[snippetSender]?.firstName.orEmpty(), body = snippet, timestamp = lastActivityMs,
            isOutgoing = snippetSender.isNotEmpty() && snippetSender == s.selfId
        )
        return Chat(
            id = chatIdPrefix + key,
            name = name,
            network = messenger.network,
            lastMessage = lastMessage,
            unreadCount = if (lastActivityMs > lastReadMs && lastMessage.isOutgoing.not()) 1 else 0,
            isGroup = isGroup,
            avatarUrl = null,
            isEncrypted = isEncrypted,
            typingNames = s.typing[key].orEmpty().mapNotNull { s.contacts[it]?.name },
            isOnDevice = true
        )
    }

    private fun StoredMessage.toMessage(s: Snapshot): Message {
        val attachment = s.attachments[id]
        val effectiveKind = attachment?.kind ?: kind
        val type = when {
            isUnsent -> MessageType.NOTICE
            effectiveKind == "image" -> MessageType.IMAGE
            effectiveKind == "video" -> MessageType.VIDEO
            effectiveKind == "audio" -> MessageType.AUDIO
            effectiveKind == "file" -> MessageType.FILE
            effectiveKind == "sticker" -> MessageType.STICKER
            effectiveKind == "text" -> MessageType.TEXT
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
        val isOutgoing = senderId.isNotEmpty() && senderId == s.selfId
        val media = attachment?.media?.takeIf { it.url.isNotBlank() }
        return Message(
            id = id,
            chatId = chatIdPrefix + threadKey,
            senderId = senderId,
            senderName = s.contacts[senderId]?.name.orEmpty(),
            body = when {
                isUnsent -> "Message unsent"
                // With media the body is the caption, or the file name (which the bubble doesn't repeat as a caption)
                media != null -> text.ifBlank { media.fileName.orEmpty() }
                placeholder.isNotEmpty() && text.isNotBlank() -> "$placeholder · $text"
                placeholder.isNotEmpty() -> placeholder
                else -> text
            },
            timestamp = timestampMs,
            isOutgoing = isOutgoing,
            // Encrypted media isn't downloaded on the device yet; without media it's a text bubble
            type = if (type in MEDIA_TYPES && media == null) MessageType.TEXT else type,
            status = status,
            reactions = reactions,
            media = media,
            isReadByOthers = isOutgoing && s.readWatermarks[threadKey].orEmpty()
                .any { (contact, watermark) -> contact != s.selfId && watermark >= timestampMs }
        )
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: 0L
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: false

    companion object {
        const val MESSENGER_CHAT_ID_PREFIX = "fbdevice:"
        const val WHATSAPP_CHAT_ID_PREFIX = "wadevice:"
        private const val CACHE_WRITE_DELAY_MILLIS = 2_000L
        private val cacheJson = Json { ignoreUnknownKeys = true }
        private val MEDIA_TYPES = setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.FILE, MessageType.STICKER)
        private const val CLOCK_SKEW_MS = 5_000L
    }
}
