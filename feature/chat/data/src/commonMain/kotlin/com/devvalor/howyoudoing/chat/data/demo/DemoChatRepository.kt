package com.devvalor.howyoudoing.chat.data.demo

import com.devvalor.howyoudoing.chat.domain.Chat
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

    override suspend fun sendMessage(chatId: String, body: String): EmptyResult<DataError.Remote> {
        val demo = chats.value[chatId] ?: return Result.Failure(DataError.Remote.NOT_FOUND)
        val id = "demo-${now()}"
        append(chatId, message(chatId, id, myUserId, "You", body, now(), MessageStatus.SENDING))
        applicationScope.launch {
            delay(500)
            updateMessage(chatId, id) { it.copy(status = MessageStatus.SENT) }
            delay(1_800)
            val reply = demo.replies.randomOrNull() ?: return@launch
            val sender = demo.messages.lastOrNull { !it.isOutgoing } ?: return@launch
            append(chatId, message(chatId, "demo-${now()}", sender.senderId, sender.senderName, reply, now()))
            chats.update { all ->
                val target = all[chatId] ?: return@update all
                all + (chatId to target.copy(chat = target.chat.copy(unreadCount = target.chat.unreadCount + 1)))
            }
        }
        return Result.Success(Unit)
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
            all + (chatId to target.copy(messages = target.messages.map { if (it.id == messageId) transform(it) else it }))
        }
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private fun message(
        chatId: String,
        id: String,
        senderId: String,
        senderName: String,
        body: String,
        timestamp: Long,
        status: MessageStatus = MessageStatus.SENT
    ) = Message(
        id = id,
        chatId = chatId,
        senderId = senderId,
        senderName = senderName,
        body = body,
        timestamp = timestamp,
        isOutgoing = senderId == myUserId,
        status = status
    )

    private fun seed(): Map<String, DemoChat> {
        val now = now()
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour

        fun chat(
            id: String,
            name: String,
            network: ChatNetwork,
            unread: Int,
            isGroup: Boolean,
            replies: List<String>,
            vararg lines: Triple<String, String, Long>
        ): Pair<String, DemoChat> {
            val messages = lines.mapIndexed { index, (sender, body, ago) ->
                val senderId = if (sender == "You") myUserId else "@${network.name.lowercase()}_$index:howyoudoin.local"
                message(id, "$id-$index", senderId, sender, body, now - ago)
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
                Triple("Mom", "Did you eat today?", 3 * hour),
                Triple("You", "Yes mom 😄", 3 * hour - 5 * minute),
                Triple("Mom", "Are you coming for dinner on Sunday?", 12 * minute),
                Triple("Mom", "Dad is making pierogi", 11 * minute),
            ),
            chat(
                "wa-hiking", "Weekend Hiking 🏔️", ChatNetwork.WHATSAPP, unread = 5, isGroup = true,
                replies = listOf("Count me in!", "Weather looks great 🌤️", "I'll bring snacks"),
                Triple("Kasia", "Who's in for Saturday?", 2 * hour),
                Triple("Tomek", "Me! What time?", 2 * hour - 10 * minute),
                Triple("Kasia", "Meeting at 7:00 at the station", 50 * minute),
                Triple("Ola", "Can someone grab an extra map?", 40 * minute),
            ),
            chat(
                "msg-tom", "Tom Becker", ChatNetwork.MESSENGER, unread = 0, isGroup = false,
                replies = listOf("Haha nice", "Sounds good 👍", "Let me check and get back to you"),
                Triple("Tom Becker", "Did you see the match yesterday?", day + 2 * hour),
                Triple("You", "That last-minute goal was insane", day + hour),
                Triple("Tom Becker", "Unreal. Drinks on Friday?", 5 * hour),
                Triple("You", "Sure, the usual place", 4 * hour),
            ),
            chat(
                "msg-bookclub", "Book Club 📚", ChatNetwork.MESSENGER, unread = 1, isGroup = true,
                replies = listOf("Finished chapter 5!", "No spoilers please 🙈"),
                Triple("Anna", "Next pick: Project Hail Mary", 3 * day),
                Triple("Mark", "Already started it, so good", 2 * day),
                Triple("Anna", "Meeting moved to Thursday", 30 * minute),
            ),
            chat(
                "wa-kasia", "Kasia Nowak", ChatNetwork.WHATSAPP, unread = 0, isGroup = false,
                replies = listOf("Thanks!", "👍"),
                Triple("You", "Sending you the photos from the trip", 6 * day),
                Triple("Kasia Nowak", "These are amazing, thank you!", 6 * day - hour),
            ),
            chat(
                "msg-anna", "Anna Schmidt", ChatNetwork.MESSENGER, unread = 0, isGroup = false,
                replies = listOf("Perfect", "See you there"),
                Triple("Anna Schmidt", "Could you review my slides?", 9 * day),
                Triple("You", "Sure, send them over", 9 * day - 2 * hour),
            ),
        )
    }
}
