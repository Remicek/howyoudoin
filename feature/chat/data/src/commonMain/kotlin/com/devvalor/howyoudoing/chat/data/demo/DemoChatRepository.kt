package com.devvalor.howyoudoing.chat.data.demo

import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.chat.domain.Reaction
import com.devvalor.howyoudoing.chat.domain.ReplyPreview
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/** Offline sample data so the app can be explored without a homeserver or bridges. */
@OptIn(ExperimentalTime::class)
class DemoChatRepository(
    private val applicationScope: CoroutineScope
) : ChatRepository {

    private data class DemoChat(
        val chat: Chat,
        val messages: List<Message>,
        val replies: List<String>
    )

    private val myUserId = AuthInfo.demo().userId
    private val chats = MutableStateFlow(seed())

    override fun observeSyncState(): Flow<SyncState> = flowOf(SyncState.SYNCED)

    override fun observeChats(): Flow<List<Chat>> =
        chats.map { all ->
            all.values
                .map { it.chat.copy(lastMessage = it.messages.lastOrNull()) }
                .sortedByDescending { it.lastActivity }
        }

    override fun observeChat(chatId: String): Flow<Chat?> =
        chats.map { it[chatId]?.let { demo -> demo.chat.copy(lastMessage = demo.messages.lastOrNull()) } }
            .distinctUntilChanged()

    override fun observeMessages(chatId: String): Flow<List<Message>> =
        chats.map { it[chatId]?.messages.orEmpty() }.distinctUntilChanged()

    override fun hasMoreHistory(chatId: String): Flow<Boolean> = flowOf(false)

    override suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote> =
        Result.Success(Unit)

    override suspend fun sendAttachment(
        chatId: String,
        attachment: Attachment,
        caption: String?
    ): EmptyResult<DataError.Remote> =
        sendMessage(chatId, caption ?: "📎 ${attachment.fileName}")

    override suspend fun toggleReaction(chatId: String, messageId: String, key: String): EmptyResult<DataError.Remote> {
        chats.update { all ->
            val target = all[chatId] ?: return@update all
            all + (chatId to target.copy(messages = target.messages.map { message ->
                if (message.id != messageId) return@map message
                val existing = message.reactions.firstOrNull { it.key == key }
                val reactions = when {
                    existing?.isOwn == true -> message.reactions.mapNotNull {
                        if (it.key == key) it.copy(count = it.count - 1, ownReactionId = null)
                            .takeIf { r -> r.count > 0 } else it
                    }

                    existing != null -> message.reactions.map {
                        if (it.key == key) it.copy(
                            count = it.count + 1,
                            ownReactionId = "demo"
                        ) else it
                    }

                    else -> message.reactions + Reaction(key, 1, "demo")
                }
                message.copy(reactions = reactions)
            }))
        }
        return Result.Success(Unit)
    }

    override suspend fun setTyping(chatId: String, isTyping: Boolean): EmptyResult<DataError.Remote> =
        Result.Success(Unit)

    override suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean): MediaRequest? = null

    override suspend fun sendMessage(chatId: String, body: String, replyToId: String?): EmptyResult<DataError.Remote> {
        val demo = chats.value[chatId] ?: return Result.Failure(DataError.Remote.NOT_FOUND)
        val id = "demo-${now()}"
        val replyTo = replyToId?.let { rid -> demo.messages.firstOrNull { it.id == rid } }
            ?.let { ReplyPreview(it.id, it.senderName, it.body) }
        append(chatId, message(chatId, id, Author(myUserId, "You"), body, now())
            .copy(status = MessageStatus.SENDING, replyTo = replyTo))
        applicationScope.launch {
            delay(500.milliseconds)
            updateMessage(chatId, id) { it.copy(status = MessageStatus.SENT) }
            delay(1_800.milliseconds)
            val reply = demo.replies.randomOrNull() ?: return@launch
            val sender = demo.messages.lastOrNull { !it.isOutgoing } ?: return@launch
            append(chatId, message(chatId, "demo-${now()}", Author(sender.senderId, sender.senderName), reply, now()))
            chats.update { all ->
                val target = all[chatId] ?: return@update all
                all + (chatId to target.copy(chat = target.chat.copy(unreadCount = target.chat.unreadCount + 1)))
            }
        }
        return Result.Success(Unit)
    }

    override suspend fun refresh() {
        delay(500.milliseconds)
    }

    override suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> {
        chats.update { all ->
            val target = all[chatId] ?: return@update all
            all + (chatId to target.copy(chat = target.chat.copy(unreadCount = 0)))
        }
        return Result.Success(Unit)
    }

    private fun append(chatId: String, message: Message) {
        chats.update { all ->
            val target = all[chatId] ?: return@update all
            all + (chatId to target.copy(messages = target.messages + message))
        }
    }

    private fun updateMessage(chatId: String, messageId: String, transform: (Message) -> Message) {
        chats.update { all ->
            val target = all[chatId] ?: return@update all
            val messages = target.messages.map { if (it.id == messageId) transform(it) else it }
            all + (chatId to target.copy(messages = messages))
        }
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private data class Author(val id: String, val name: String)

    private fun message(chatId: String, id: String, author: Author, body: String, timestamp: Long) = Message(
        id = id,
        chatId = chatId,
        senderId = author.id,
        senderName = author.name,
        body = body,
        timestamp = timestamp,
        isOutgoing = author.id == myUserId,
        status = MessageStatus.SENT
    )

    private fun seed(): Map<String, DemoChat> {
        val now = now()

        fun chat(
            id: String,
            name: String,
            network: ChatNetwork,
            unread: Int,
            isGroup: Boolean,
            replies: List<String>,
            vararg lines: Triple<String, String, Duration>
        ): Pair<String, DemoChat> {
            val messages = lines.mapIndexed { index, (sender, body, ago) ->
                val senderId = if (sender == "You") myUserId else "@${network.name.lowercase()}_$index:howyoudoin.local"
                message(id, "$id-$index", Author(senderId, sender), body, now - ago.inWholeMilliseconds)
            }
            return id to DemoChat(
                chat = Chat(id, name, network, messages.lastOrNull(), unread, isGroup),
                messages = messages,
                replies = replies
            )
        }

        return mapOf(
            chat(
                "wa-mom", "Mom", ChatNetwork.WHATSAPP, unread = 2, isGroup = false,
                replies = listOf("❤️", "Call me when you can", "Ok, see you Sunday!"),
                Triple("Mom", "Did you eat today?", 3.hours),
                Triple("You", "Yes mom 😄", 3.hours - 5.minutes),
                Triple("Mom", "Are you coming for dinner on Sunday?", 12.minutes),
                Triple("Mom", "Dad is making pierogi", 11.minutes),
            ),
            chat(
                "wa-hiking", "Weekend Hiking 🏔️", ChatNetwork.WHATSAPP, unread = 5, isGroup = true,
                replies = listOf("Count me in!", "Weather looks great 🌤️", "I'll bring snacks"),
                Triple("Kasia", "Who's in for Saturday?", 2.hours),
                Triple("Tomek", "Me! What time?", 2.hours - 10.minutes),
                Triple("Kasia", "Meeting at 7:00 at the station", 50.minutes),
                Triple("Ola", "Can someone grab an extra map?", 40.minutes),
            ),
            chat(
                "msg-tom", "Tom Becker", ChatNetwork.MESSENGER, unread = 0, isGroup = false,
                replies = listOf("Haha nice", "Sounds good 👍", "Let me check and get back to you"),
                Triple("Tom Becker", "Did you see the match yesterday?", 1.days + 2.hours),
                Triple("You", "That last-minute goal was insane", 1.days + 1.hours),
                Triple("Tom Becker", "Unreal. Drinks on Friday?", 5.hours),
                Triple("You", "Sure, the usual place", 4.hours),
            ),
            chat(
                "msg-bookclub", "Book Club 📚", ChatNetwork.MESSENGER, unread = 1, isGroup = true,
                replies = listOf("Finished chapter 5!", "No spoilers please 🙈"),
                Triple("Anna", "Next pick: Project Hail Mary", 3.days),
                Triple("Mark", "Already started it, so good", 2.days),
                Triple("Anna", "Meeting moved to Thursday", 30.minutes),
            ),
            chat(
                "wa-kasia", "Kasia Nowak", ChatNetwork.WHATSAPP, unread = 0, isGroup = false,
                replies = listOf("Thanks!", "👍"),
                Triple("You", "Sending you the photos from the trip", 6.days),
                Triple("Kasia Nowak", "These are amazing, thank you!", 6.days - 1.hours),
            ),
            chat(
                "msg-anna", "Anna Schmidt", ChatNetwork.MESSENGER, unread = 0, isGroup = false,
                replies = listOf("Perfect", "See you there"),
                Triple("Anna Schmidt", "Could you review my slides?", 9.days),
                Triple("You", "Sure, send them over", 9.days - 2.hours),
            ),
        )
    }

    override suspend fun downloadMedia(mxcUrl: String): ByteArray? = null
}
