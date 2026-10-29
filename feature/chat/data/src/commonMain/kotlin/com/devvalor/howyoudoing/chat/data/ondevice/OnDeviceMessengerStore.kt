package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.chat.domain.ChatContact
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.MediaInfo
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.chat.domain.Reaction
import com.devvalor.howyoudoing.chat.domain.ReplyPreview
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
        val readWatermarks: Map<String, Map<String, Long>> = emptyMap(),
        /** Thread key → its picture (group photo, or a WhatsApp contact's profile picture). */
        val avatars: Map<String, String> = emptyMap()
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
        val height: Int?,
        val durationMs: Long? = null
    ) {
        val media: MediaInfo
            get() = MediaInfo(
                url,
                mimeType,
                fileName,
                sizeBytes = null,
                width,
                height,
                durationMillis = durationMs
            )
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
        val isEncrypted: Boolean = false,
        /** For media not downloaded with the message: what the client needs to download it on request. */
        val mediaRef: String? = null,
        val isEdited: Boolean = false,
        /** Length of audio or video, when the network says. */
        val durationMs: Long? = null,
        /** The message this one replies to. */
        val replyToId: String? = null,
        /** For local echoes: can be sent again if sending fails (text; attachments aren't kept). */
        val canRetry: Boolean = false
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
            .runningFold(null as OnDeviceMessengerState? to (null as OnDeviceMessengerState?)) {
                    (_, previous), current ->
                previous to current
            }
            .onEach { (previous, current) ->
                val wasLinked = previous != null && previous != OnDeviceMessengerState.NotLinked
                if (wasLinked && current == OnDeviceMessengerState.NotLinked) {
                    _snapshot.value = Snapshot()
                    cache.clear()
                }
            }
            .launchIn(scope)
        // Pictures of known chats (WhatsApp sends them only on request; their links also expire, so every connect)
        messenger.state
            .filter { it is OnDeviceMessengerState.Connected }
            .distinctUntilChanged()
            .onEach { messenger.fetchAvatars(avatarKeysToRefresh()) }
            .launchIn(scope)
        _snapshot
            .drop(1)
            .debounce(CACHE_WRITE_DELAY_MILLIS)
            .onEach { snapshot ->
                // Typing and messages still being sent only make sense while the app runs; failed ones are kept so
                // they can be sent again (or deleted) later
                val failed = snapshot.pending.filterValues { it.status == MessageStatus.FAILED }
                runCatching {
                    cache.write(
                        cacheJson.encodeToString(
                            snapshot.copy(
                                typing = emptyMap(),
                                pending = failed
                            )
                        )
                    )
                }
            }
            .launchIn(scope)
    }

    fun addPending(threadKey: String, otid: String, text: String, replyToId: String? = null, canRetry: Boolean = true) =
        _snapshot.update { s ->
            s.copy(
                pending = s.pending + (otid to StoredMessage(
                    id = "pending-$otid",
                    threadKey = threadKey,
                    otid = otid,
                    senderId = s.selfId.orEmpty(),
                    text = text,
                    timestampMs = Clock.System.now().toEpochMilliseconds(),
                    isUnsent = false,
                    status = MessageStatus.SENDING,
                    replyToId = replyToId,
                    canRetry = canRetry
                ))
            )
        }

    /** A local echo that failed to send, by its message ID. */
    fun failedMessage(messageId: String): StoredMessage? =
        _snapshot.value.pending.values.firstOrNull { it.id == messageId && it.status == MessageStatus.FAILED }

    /** Forgets a local echo (one that failed, being sent again or deleted). */
    fun removePending(messageId: String) = _snapshot.update { s ->
        s.copy(pending = s.pending.filterValues { it.id != messageId })
    }

    /** A stored message of a chat, by ID. */
    fun message(threadKey: String, messageId: String): Message? {
        val s = _snapshot.value
        return s.messages[threadKey]?.get(messageId)?.toMessage(s)
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
        _snapshot.update { current -> current.withAvatar(event).reduce(event) }
    }

    /** A thread's picture can come with its thread event (Messenger) or on its own (WhatsApp). */
    private fun Snapshot.withAvatar(event: JsonObject): Snapshot {
        val pictureKey = event.str(if (event.str("type") == "avatar") "threadKey" else "key")
        val picture = event.str(if (event.str("type") == "avatar") "url" else "pictureUrl")
        return if (pictureKey.isNotEmpty() && picture.isNotEmpty()) {
            copy(avatars = avatars + (pictureKey to picture))
        } else {
            this
        }
    }

    private fun Snapshot.reduce(event: JsonObject): Snapshot = when (event.str("type")) {
        "self" -> copy(selfId = event.str("id"))
        "contact" -> withContact(event)
        // Group names from WhatsApp's group list carry no activity; don't let them reset it
        "thread" if event.bool("nameOnly") -> withThreadName(event)
        "thread" -> withThread(event)
        "participant" -> withParticipant(event)
        "range" -> withRange(event)
        "attachment" -> withAttachment(event)
        "typing" -> withTyping(event)
        "read" -> withReadReceipt(event)
        "link" -> withLink(event)
        "reaction" -> withReaction(event)
        "edit", "unsend" -> withChangedMessage(event)
        "message" -> withMessageEvent(event)
        else -> this
    }

    private fun Snapshot.withContact(event: JsonObject): Snapshot =
        event.str("id").takeIf { it.isNotEmpty() }?.let { id ->
            // WhatsApp's contact events carry only names; keep a picture learned before
            val avatar = event.str("avatarUrl").ifBlank { null } ?: contacts[id]?.avatarUrl
            copy(contacts = contacts + (id to Contact(id, event.str("name"), event.str("firstName"), avatar)))
        } ?: this

    private fun Snapshot.withThreadName(event: JsonObject): Snapshot =
        event.str("key").takeIf { it.isNotEmpty() }?.let { key ->
            val existing = threads[key]
            val named = existing?.copy(name = event.str("name").ifBlank { existing.name }) ?: Thread(
                key = key,
                name = event.str("name"),
                isGroup = event.bool("isGroup"),
                isEncrypted = event.bool("isEncrypted"),
                lastActivityMs = 0L,
                lastReadMs = 0L,
                snippet = "",
                snippetSender = ""
            )
            copy(threads = threads + (key to named))
        } ?: this

    private fun Snapshot.withThread(event: JsonObject): Snapshot =
        event.str("key").takeIf { it.isNotEmpty() }?.let { key ->
            val thread = Thread(
                key = key,
                name = event.str("name"),
                isGroup = event.bool("isGroup"),
                isEncrypted = event.bool("isEncrypted"),
                lastActivityMs = event.long("lastActivityMs"),
                lastReadMs = event.long("lastReadMs"),
                snippet = event.str("snippet"),
                snippetSender = event.str("snippetSender")
            )
            copy(threads = threads + (key to thread))
        } ?: this

    private fun Snapshot.withParticipant(event: JsonObject): Snapshot {
        val key = event.str("threadKey")
        val contact = event.str("contactId")
        return if (key.isEmpty() || contact.isEmpty()) {
            this
        } else {
            copy(participants = participants + (key to (participants[key].orEmpty() + contact)))
        }
    }

    private fun Snapshot.withRange(event: JsonObject): Snapshot {
        val key = event.str("threadKey")
        return when {
            key.isEmpty() -> this
            event.bool("isEncrypted") -> copy(
                hasMoreBefore = hasMoreBefore + (key to event.bool("hasMoreBefore")),
                historyCursors = historyCursors + (key to event.long("minTimestampMs"))
            )

            else -> copy(hasMoreBefore = hasMoreBefore + (key to event.bool("hasMoreBefore")))
        }
    }

    private fun Snapshot.withAttachment(event: JsonObject): Snapshot {
        val messageId = event.str("messageId")
        if (messageId.isEmpty() || messageId in attachments) return this
        val attachment = StoredAttachment(
            kind = event.str("kind"),
            url = event.str("url"),
            mimeType = event.str("mimeType").ifBlank { null },
            fileName = event.str("fileName").ifBlank { null },
            width = event.long("width").toInt().takeIf { it > 0 },
            height = event.long("height").toInt().takeIf { it > 0 },
            durationMs = event.long("durationMs").takeIf { it > 0 }
        )
        return copy(attachments = attachments + (messageId to attachment))
    }

    private fun Snapshot.withTyping(event: JsonObject): Snapshot {
        val key = event.str("threadKey")
        val sender = event.str("senderId")
        if (key.isEmpty() || sender.isEmpty() || sender == selfId) return this
        val current = typing[key].orEmpty()
        val now = if (event.bool("isTyping")) current + sender else current - sender
        return copy(typing = typing + (key to now))
    }

    private fun Snapshot.withReadReceipt(event: JsonObject): Snapshot {
        val key = event.str("threadKey")
        val contact = event.str("contactId")
        if (key.isEmpty() || contact.isEmpty()) return this
        val current = readWatermarks[key].orEmpty()
        val watermark = maxOf(current[contact] ?: 0L, event.long("watermarkMs"))
        val read = copy(readWatermarks = readWatermarks + (key to (current + (contact to watermark))))
        // Our own receipt: the chat was read on another device (e.g. the network's own app)
        val ownThread = threads[key]
        return if (contact == selfId && ownThread != null && watermark > ownThread.lastReadMs) {
            read.copy(threads = threads + (key to ownThread.copy(lastReadMs = watermark)))
        } else {
            read
        }
    }

    private fun Snapshot.withLink(event: JsonObject): Snapshot {
        val fbKey = event.str("fbThreadKey")
        val key = event.str("threadKey")
        if (fbKey.isEmpty() || key.isEmpty()) return this
        return copy(links = links + (fbKey to Link(key, event.bool("isGroup"), event.long("lastActivityMs"))))
    }

    private fun Snapshot.withReaction(event: JsonObject): Snapshot {
        val key = event.str("threadKey")
        val target = event.str("targetId")
        val sender = event.str("senderId")
        if (key.isEmpty() || target.isEmpty() || sender.isEmpty()) return this
        val byMessage = reactions[key].orEmpty()
        val bySender = byMessage[target].orEmpty().let { current ->
            val emoji = event.str("emoji")
            if (emoji.isEmpty()) current - sender else current + (sender to emoji)
        }
        return copy(reactions = reactions + (key to (byMessage + (target to bySender))))
    }

    private fun Snapshot.withChangedMessage(event: JsonObject): Snapshot {
        // Messenger edits come without a thread key; the message ID is unique anyway
        val messageId = event.str("messageId")
        val threadKey = event.str("threadKey").ifEmpty {
            messages.entries.firstOrNull { messageId in it.value }?.key.orEmpty()
        }
        val original = messages[threadKey]?.get(messageId) ?: return this
        val changed =
            if (event.str("type") == "edit") {
                original.copy(text = event.str("text"), isEdited = true)
            } else {
                original.copy(isUnsent = true, text = "", mediaRef = null)
            }
        return copy(messages = messages + (threadKey to (messages.getValue(threadKey) + (messageId to changed))))
    }

    private fun Snapshot.withMessageEvent(event: JsonObject): Snapshot {
        val message = StoredMessage(
            id = event.str("id"),
            threadKey = event.str("threadKey"),
            otid = event.str("otid"),
            senderId = event.str("senderId"),
            text = event.str("text"),
            timestampMs = event.long("timestampMs"),
            isUnsent = event.bool("isUnsent"),
            kind = event.str("kind").ifEmpty { "text" },
            isEncrypted = event.bool("isEncrypted"),
            mediaRef = event.str("mediaRef").ifEmpty { null },
            durationMs = event.long("durationMs").takeIf { it > 0 },
            replyToId = event.str("replyToId").ifEmpty { null }
        )
        val thread = messages[message.threadKey].orEmpty()
        return copy(
            messages = messages + (message.threadKey to (thread + (message.id to message))),
            pending = pending - message.otid,
            threads = threads.withMessage(
                message,
                isOutgoing = message.senderId.isNotEmpty() && message.senderId == selfId
            )
        )
    }

    private fun quotedKind(message: StoredMessage, s: Snapshot): String =
        when (s.attachments[message.id]?.kind ?: message.kind) {
            "image" -> "📷 Photo"
            "video" -> "🎬 Video"
            "audio" -> "🎤 Voice message"
            "sticker" -> "Sticker"
            "file" -> "📎 File"
            else -> "Message"
        }

    /**
     * A new message moves its chat's last activity on, so it counts as unread (or, sent by us, as read). Some
     * networks (Instagram) only report a chat's activity when the inbox is loaded.
     */
    private fun Map<String, Thread>.withMessage(message: StoredMessage, isOutgoing: Boolean): Map<String, Thread> {
        val thread = this[message.threadKey] ?: return this
        if (message.timestampMs <= thread.lastActivityMs) return this
        val updated = thread.copy(
            lastActivityMs = message.timestampMs,
            lastReadMs = if (isOutgoing) maxOf(thread.lastReadMs, message.timestampMs) else thread.lastReadMs
        )
        return this + (message.threadKey to updated)
    }

    fun chats(): List<Chat> {
        val s = _snapshot.value
        // A Facebook thread that moved to an encrypted chat is shown as that chat
        val replaced = s.links.filter { (fbKey, link) -> fbKey != link.threadKey }.keys
        return allThreads(s).values
            .filter { it.key !in replaced }
            // WhatsApp's system account (0@s.whatsapp.net) isn't a chat
            .filterNot { it.key.startsWith("0@") }
            // Known chats without any activity yet (e.g. from a contact or group list) would only clutter the inbox
            .filter { it.lastActivityMs > 0 || s.messages[it.key].orEmpty().isNotEmpty() }
            .map { thread -> thread.toChat(s) }
    }

    fun thread(threadKey: String): Thread? = allThreads(_snapshot.value)[threadKey]

    /** The people this network told the app about, with their one-to-one chat where there is one. */
    fun contacts(network: ChatNetwork): List<ChatContact> {
        val s = _snapshot.value
        val directChats = s.threads.values.filter { !it.isGroup }.mapNotNull { thread ->
            s.participants[thread.key]?.singleOrNull { it != s.selfId }?.let { it to thread.key }
        }.toMap()
        return s.contacts.values
            .filter { it.id != s.selfId && it.name.isNotBlank() }
            .map { contact ->
                ChatContact(
                    id = contact.id,
                    name = contact.name,
                    network = network,
                    avatarUrl = contact.avatarUrl,
                    chatId = directChats[contact.id]?.let { chatIdPrefix + it }
                )
            }
    }

    /**
     * Starts a one-to-one chat locally (the network creates it with the first message) so it can be opened; it shows
     * at the top of the inbox. Returns its chat ID.
     */
    fun startDirectChat(threadKey: String, contactId: String, name: String): String {
        _snapshot.update { s ->
            if (threadKey in s.threads) return@update s
            val now = Clock.System.now().toEpochMilliseconds()
            s.copy(
                threads = s.threads + (threadKey to Thread(threadKey, name, false, false, now, now, "", "")),
                participants = s.participants + (threadKey to setOfNotNull(contactId, s.selfId))
            )
        }
        return chatIdPrefix + threadKey
    }

    /**
     * 1:1 chats whose picture is missing or about to expire (CDN links carry their expiry as `oe=<hex seconds>`);
     * group pictures come with the thread sync.
     */
    fun avatarKeysToRefresh(): List<String> {
        val soon = Clock.System.now().epochSeconds + AVATAR_REFRESH_AHEAD_SECONDS
        return chats().filter { !it.isGroup }.filter { chat ->
            val expiry = chat.avatarUrl?.let { EXPIRY_PARAM.find(it)?.groupValues?.get(1)?.toLongOrNull(HEX) }
            chat.avatarUrl == null || (expiry != null && expiry < soon)
        }.map { it.id.removePrefix(chatIdPrefix) }
    }

    fun mediaRef(threadKey: String, messageId: String): String? =
        _snapshot.value.messages[threadKey]?.get(messageId)?.mediaRef

    /**
     * Thread rows, plus encrypted chats known only from a link (no thread row) or only from live messages
     * (a chat that started after connecting).
     */
    private fun allThreads(s: Snapshot): Map<String, Thread> {
        val fromLinks = s.links.values.filter { it.threadKey !in s.threads }.associate { link ->
            link.threadKey to Thread(
                link.threadKey,
                "",
                link.isGroup,
                true,
                link.lastActivityMs,
                link.lastActivityMs,
                "",
                ""
            )
        }
        val fromMessages = s.messages
            .filterKeys { it !in s.threads && it !in fromLinks }
            .mapNotNull { (key, messages) ->
                val newest = messages.values.maxByOrNull { it.timestampMs } ?: return@mapNotNull null
                if (!newest.isEncrypted) return@mapNotNull null
                // WhatsApp group JIDs are on g.us; Messenger's encrypted groups are known from their links
                key to Thread(key, "", key.endsWith("@g.us"), true, newest.timestampMs, 0L, "", "")
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
                if (isGroup) people.take(NAMED_MEMBERS)
                    .joinToString(", ") { it.firstName.ifBlank { it.name } } else people.firstOrNull()?.name.orEmpty()
            }
        }.ifBlank {
            when {
                isGroup -> "Group chat"
                // WhatsApp chats are keyed by JID: show the phone number until the contact name is known
                key.endsWith("@s.whatsapp.net") -> "+" + key.substringBefore("@")
                else -> "${messenger.network.displayName} chat"
            }
        }
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
            avatarUrl = s.avatars[key]
                ?: if (isGroup) null else others.firstNotNullOfOrNull { s.contacts[it]?.avatarUrl },
            isEncrypted = isEncrypted,
            typingNames = s.typing[key].orEmpty().mapNotNull { s.contacts[it]?.name },
            isOnDevice = true
        )
    }

    private fun StoredMessage.toMessage(s: Snapshot): Message {
        val attachment = s.attachments[id]
        val type = messageType(attachment?.kind ?: kind, isUnsent)
        val isOutgoing = senderId.isNotEmpty() && senderId == s.selfId
        val media = mediaFor(attachment, type)
        return Message(
            id = id,
            chatId = chatIdPrefix + threadKey,
            senderId = senderId,
            senderName = s.contacts[senderId]?.name.orEmpty(),
            body = bodyWith(media, placeholderFor(type)),
            timestamp = timestampMs,
            isOutgoing = isOutgoing,
            canRetry = canRetry && status == MessageStatus.FAILED,
            // Encrypted media isn't downloaded on the device yet; without media it's a text bubble
            type = if (type in MEDIA_TYPES && media == null) MessageType.TEXT else type,
            status = status,
            reactions = reactionsIn(s),
            media = media,
            isEdited = isEdited,
            replyTo = replyToId?.takeIf { !isUnsent }?.let { replyPreview(it, s) },
            isReadByOthers = isOutgoing && s.readWatermarks[threadKey].orEmpty()
                .any { (contact, watermark) -> contact != s.selfId && watermark >= timestampMs }
        )
    }

    private fun messageType(kind: String, isUnsent: Boolean): MessageType = when {
        isUnsent -> MessageType.NOTICE
        kind == "image" -> MessageType.IMAGE
        kind == "video" -> MessageType.VIDEO
        kind == "audio" -> MessageType.AUDIO
        kind == "file" -> MessageType.FILE
        kind == "sticker" -> MessageType.STICKER
        kind == "text" -> MessageType.TEXT
        else -> MessageType.OTHER
    }

    /** Media isn't downloaded on the device yet, so it's described in text. */
    private fun placeholderFor(type: MessageType): String = when (type) {
        MessageType.IMAGE -> "📷 Photo"
        MessageType.VIDEO -> "🎬 Video"
        MessageType.AUDIO -> "🎤 Voice message"
        MessageType.FILE -> "📎 File"
        MessageType.STICKER -> "Sticker"
        MessageType.OTHER -> "Unsupported message"
        else -> ""
    }

    private fun StoredMessage.reactionsIn(s: Snapshot): List<Reaction> =
        s.reactions[threadKey]?.get(id).orEmpty()
            .entries.groupBy({ it.value }, { it.key })
            .map { (emoji, senders) ->
                val ownReactionId = if (s.selfId in senders) "own-$emoji" else null
                Reaction(key = emoji, count = senders.size, ownReactionId = ownReactionId)
            }

    private fun StoredMessage.mediaFor(attachment: StoredAttachment?, type: MessageType): MediaInfo? =
        attachment?.media?.takeIf { it.url.isNotBlank() && !isUnsent }
            ?.let { if (it.durationMillis == null && durationMs != null) it.copy(durationMillis = durationMs) else it }
        // Not downloaded yet: shown as a card that downloads it when opened
            ?: mediaRef?.takeIf { type in ON_REQUEST_TYPES }?.let { onRequestMedia(type) }

    private fun StoredMessage.onRequestMedia(type: MessageType): MediaInfo {
        // The real type arrives with the download; until then a typical one, so the file opens in a player
        val (mimeType, fileName) = when (type) {
            MessageType.VIDEO -> "video/mp4" to "video.mp4"
            MessageType.AUDIO -> "audio/ogg" to "voice.ogg"
            else -> null to text.ifBlank { null }
        }
        val url = onRequestMediaUrl(chatIdPrefix + threadKey, id)
        return MediaInfo(url, mimeType, fileName, null, null, null, durationMs)
    }

    private fun StoredMessage.bodyWith(media: MediaInfo?, placeholder: String): String = when {
        isUnsent -> "Message unsent"
        // With media the body is the caption, or the file name (which the bubble doesn't repeat as a caption)
        media != null -> text.ifBlank { media.fileName.orEmpty() }
        placeholder.isNotEmpty() && text.isNotBlank() -> "$placeholder · $text"
        placeholder.isNotEmpty() -> placeholder
        else -> text
    }

    private fun StoredMessage.replyPreview(replyId: String, s: Snapshot): ReplyPreview {
        val target = s.messages[threadKey]?.get(replyId)
        return ReplyPreview(
            messageId = replyId,
            senderName = target?.let {
                val contact = s.contacts[it.senderId]
                if (it.senderId == s.selfId) "You" else contact?.firstName ?: contact?.name
            }.orEmpty(),
            body = target?.let { quoted ->
                if (quoted.isUnsent) "Message unsent" else quoted.text.ifBlank { quotedKind(quoted, s) }
            } ?: "Original message"
        )
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: 0L
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: false

    companion object {
        const val MESSENGER_CHAT_ID_PREFIX = "fbdevice:"
        const val WHATSAPP_CHAT_ID_PREFIX = "wadevice:"
        const val INSTAGRAM_CHAT_ID_PREFIX = "igdevice:"
        const val TELEGRAM_CHAT_ID_PREFIX = "tgdevice:"
        const val SIGNAL_CHAT_ID_PREFIX = "sgdevice:"
        const val SLACK_CHAT_ID_PREFIX = "skdevice:"
        private const val CACHE_WRITE_DELAY_MILLIS = 2_000L
        private val cacheJson = Json { ignoreUnknownKeys = true }
        private val MEDIA_TYPES =
            setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.FILE, MessageType.STICKER)
        private const val CLOCK_SKEW_MS = 5_000L
        private const val AVATAR_REFRESH_AHEAD_SECONDS = 24 * 3600L
        private val EXPIRY_PARAM = Regex("[?&]oe=([0-9A-Fa-f]+)")
        private val ON_REQUEST_TYPES = setOf(MessageType.VIDEO, MessageType.AUDIO, MessageType.FILE)
        private const val ON_REQUEST_SCHEME = "ondevice-media:"

        fun onRequestMediaUrl(chatId: String, messageId: String) = "$ON_REQUEST_SCHEME$chatId|$messageId"

        /** The chat ID and message ID in an [onRequestMediaUrl]. */
        fun parseOnRequestMediaUrl(url: String): Pair<String, String>? =
            url.takeIf { it.startsWith(ON_REQUEST_SCHEME) }?.removePrefix(ON_REQUEST_SCHEME)?.split('|', limit = 2)
                ?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }
}

/** The oe= expiry in Messenger's picture URLs is hexadecimal. */
private const val HEX = 16

/** How many people an unnamed group is named after. */
private const val NAMED_MEMBERS = 4
