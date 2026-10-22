package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceHistoryError
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceHistoryStatus
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceMessengerStoreTest {

    private class FakeClient : OnDeviceMessenger {
        override val isSupported = true
        override val state = MutableStateFlow<OnDeviceMessengerState>(OnDeviceMessengerState.Connected("Me"))
        override val events = MutableSharedFlow<String>(extraBufferCapacity = 64)
        override suspend fun link(cookies: Map<String, String>): EmptyResult<DataError.Remote> = Result.Success(Unit)
        override suspend fun connectIfLinked() = Unit
        override suspend fun sendText(threadKey: String, text: String, isEncrypted: Boolean, isGroup: Boolean) = Result.Success("sent")
        override suspend fun historyStatus(): Result<OnDeviceHistoryStatus, DataError.Remote> =
            Result.Success(OnDeviceHistoryStatus(isRestored = false, isBackupAvailable = false))
        override suspend fun restoreHistory(pin: String): EmptyResult<OnDeviceHistoryError> = Result.Success(Unit)
        override suspend fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long) = Result.Success(Unit)
        override suspend fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String, beforeMessageIsOutgoing: Boolean) =
            Result.Success(Unit)
        override suspend fun sendReaction(
            threadKey: String, messageId: String, emoji: String, isEncrypted: Boolean, isGroup: Boolean, messageSenderId: String
        ) = Result.Success(Unit)
        override suspend fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String) =
            Result.Success(Unit)
        override suspend fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean) = Result.Success(Unit)
        override suspend fun unlink() = Unit
    }

    private class MemoryCache : OnDeviceInboxCache {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }

    private val client = FakeClient()

    private fun TestScope.store(cache: OnDeviceInboxCache = MemoryCache()) =
        OnDeviceMessengerStore(client, cache, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

    private suspend fun emit(json: String) = client.events.emit(json)

    private suspend fun self() = emit("""{"type":"self","id":"1"}""")
    private suspend fun contact(id: String, name: String, avatar: String = "") =
        emit("""{"type":"contact","id":"$id","name":"$name","firstName":"${name.substringBefore(' ')}","avatarUrl":"$avatar"}""")
    private suspend fun message(thread: String, id: String, sender: String, text: String, ts: Long, kind: String = "text", extra: String = "") =
        emit("""{"type":"message","threadKey":"$thread","id":"$id","otid":"$id","senderId":"$sender","text":"$text","kind":"$kind","timestampMs":$ts,"isEncrypted":true$extra}""")

    @Test
    fun encrypted_chat_known_only_from_messages_is_named_after_the_other_person() = runTest {
        val store = store()
        self()
        contact("2", "Anna Nowak")
        message("2", "m1", "2", "Hi", 1_000)

        val chat = store.chats().single()
        assertEquals("fbdevice:2", chat.id)
        assertEquals("Anna Nowak", chat.name)
        assertEquals("Hi", chat.lastMessage?.body)
        assertTrue(chat.isEncrypted)
    }

    @Test
    fun whatsapp_group_known_only_from_messages_is_a_group() = runTest {
        val store = store()
        self()
        message("123@g.us", "m1", "2", "Hello group", 1_000)

        assertTrue(store.chats().single().isGroup)
    }

    @Test
    fun edits_change_the_text_and_mark_the_message_edited() = runTest {
        val store = store()
        self()
        message("2", "m1", "2", "Helo", 1_000)
        emit("""{"type":"edit","threadKey":"2","messageId":"m1","text":"Hello"}""")

        val edited = store.messages("2").single()
        assertEquals("Hello", edited.body)
        assertTrue(edited.isEdited)
    }

    @Test
    fun messenger_edits_without_a_thread_key_find_their_message() = runTest {
        val store = store()
        self()
        message("2", "m1", "2", "Helo", 1_000)
        emit("""{"type":"edit","messageId":"m1","text":"Hello"}""")

        assertEquals("Hello", store.messages("2").single().body)
    }

    @Test
    fun unsent_messages_lose_their_text_and_media() = runTest {
        val store = store()
        self()
        message("2", "m1", "2", "", 1_000, kind = "image")
        emit("""{"type":"attachment","threadKey":"2","messageId":"m1","kind":"image","url":"https://cdn/x.jpg","previewUrl":"https://cdn/x.jpg"}""")
        emit("""{"type":"unsend","threadKey":"2","messageId":"m1"}""")

        val unsent = store.messages("2").single()
        assertEquals(MessageType.NOTICE, unsent.type)
        assertNull(unsent.media)
    }

    @Test
    fun media_not_downloaded_yet_opens_on_request() = runTest {
        val store = store()
        self()
        message("2", "v1", "2", "", 1_000, kind = "video", extra = ""","mediaRef":"abc"""")

        val video = store.messages("2").single()
        assertEquals(MessageType.VIDEO, video.type)
        val url = assertNotNull(video.media).url
        assertEquals("fbdevice:2" to "v1", OnDeviceMessengerStore.parseOnRequestMediaUrl(url))
        assertEquals("abc", store.mediaRef("2", "v1"))
    }

    @Test
    fun chat_picture_comes_from_the_contact_or_an_avatar_event() = runTest {
        val store = store()
        self()
        contact("2", "Anna Nowak", avatar = "https://cdn/anna.jpg")
        message("2", "m1", "2", "Hi", 1_000)
        assertEquals("https://cdn/anna.jpg", store.chats().single().avatarUrl)

        emit("""{"type":"avatar","threadKey":"2","url":"https://cdn/anna-new.jpg"}""")
        assertEquals("https://cdn/anna-new.jpg", store.chats().single().avatarUrl)
    }

    @Test
    fun a_contact_event_without_a_picture_keeps_the_known_one() = runTest {
        val store = store()
        self()
        contact("2", "Anna Nowak", avatar = "https://cdn/anna.jpg")
        contact("2", "Anna N.")
        message("2", "m1", "2", "Hi", 1_000)

        assertEquals("https://cdn/anna.jpg", store.chats().single().avatarUrl)
    }

    @Test
    fun pictures_that_expire_soon_or_are_missing_get_refreshed() = runTest {
        val store = store()
        self()
        contact("2", "Anna Nowak", avatar = "https://cdn/a.jpg?oe=1")          // expired long ago
        contact("3", "Bob Smith", avatar = "https://cdn/b.jpg?oe=7FFFFFFF")    // far in the future
        contact("4", "Cid Jones")                                              // none
        message("2", "m1", "2", "Hi", 1_000)
        message("3", "m2", "3", "Hi", 1_000)
        message("4", "m3", "4", "Hi", 1_000)

        assertEquals(setOf("2", "4"), store.avatarKeysToRefresh().toSet())
    }

    @Test
    fun the_inbox_survives_a_restart_through_the_cache() = runTest {
        val cache = MemoryCache()
        val first = store(cache)
        self()
        contact("2", "Anna Nowak")
        message("2", "m1", "2", "Hi", 1_000)
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()

        val restarted = store(cache)
        assertEquals(first.chats().map { it.name }, restarted.chats().map { it.name })
    }

    private suspend fun thread(key: String, lastActivity: Long, lastRead: Long) =
        emit("""{"type":"thread","key":"$key","name":"Anna","isGroup":false,"isEncrypted":false,"lastActivityMs":$lastActivity,"lastReadMs":$lastRead}""")

    @Test
    fun a_new_message_after_the_chat_was_read_makes_it_unread() = runTest {
        val store = store()
        self()
        thread("2", lastActivity = 1_000, lastRead = 1_000)
        assertEquals(0, store.chats().single().unreadCount)

        // Instagram only reports a chat's activity when the inbox loads; the message itself has to move it on
        message("2", "m1", "2", "Hi", 2_000)
        assertEquals(1, store.chats().single().unreadCount)
    }

    @Test
    fun a_message_we_send_leaves_the_chat_read() = runTest {
        val store = store()
        self()
        thread("2", lastActivity = 1_000, lastRead = 1_000)
        message("2", "m1", "1", "Hello", 2_000)
        assertEquals(0, store.chats().single().unreadCount)
    }

    @Test
    fun our_own_read_receipt_from_another_device_marks_the_chat_read() = runTest {
        val store = store()
        self()
        thread("2", lastActivity = 1_000, lastRead = 1_000)
        message("2", "m1", "2", "Hi", 2_000)
        assertEquals(1, store.chats().single().unreadCount)

        emit("""{"type":"read","threadKey":"2","contactId":"1","watermarkMs":2000}""")
        assertEquals(0, store.chats().single().unreadCount)
    }

    @Test
    fun someone_elses_read_receipt_marks_our_messages_read_but_not_the_chat() = runTest {
        val store = store()
        self()
        thread("2", lastActivity = 1_000, lastRead = 1_000)
        message("2", "m1", "1", "Hello", 2_000)
        emit("""{"type":"read","threadKey":"2","contactId":"2","watermarkMs":2000}""")

        assertTrue(store.messages("2").single().isReadByOthers)
        assertEquals(0, store.chats().single().unreadCount)
    }

    @Test
    fun a_reply_shows_what_it_replies_to() = runTest {
        val store = store()
        self()
        contact("2", "Anna Nowak")
        message("2", "m1", "2", "Lunch?", 1_000)
        message("2", "m2", "1", "Sure", 2_000, extra = ""","replyToId":"m1"""")
        message("2", "m3", "2", "Great", 3_000, extra = ""","replyToId":"m2"""")

        val (_, toAnna, toUs) = store.messages("2")
        assertEquals("m1", toAnna.replyTo?.messageId)
        assertEquals("Anna", toAnna.replyTo?.senderName)
        assertEquals("Lunch?", toAnna.replyTo?.body)
        assertEquals("You", toUs.replyTo?.senderName)
        assertEquals("Sure", toUs.replyTo?.body)
    }

    @Test
    fun a_reply_to_a_message_not_loaded_says_so() = runTest {
        val store = store()
        self()
        message("2", "m2", "2", "Yes", 2_000, extra = ""","replyToId":"old"""")

        val reply = store.messages("2").single().replyTo
        assertNotNull(reply)
        assertEquals("Original message", reply.body)
    }

    @Test
    fun a_reply_to_an_unsent_message_says_it_was_unsent() = runTest {
        val store = store()
        self()
        message("2", "m1", "2", "Oops", 1_000)
        message("2", "m2", "1", "What?", 2_000, extra = ""","replyToId":"m1"""")
        emit("""{"type":"unsend","threadKey":"2","messageId":"m1"}""")

        assertEquals("Message unsent", store.messages("2").last().replyTo?.body)
    }

    @Test
    fun failed_messages_survive_a_restart_and_can_be_sent_again_or_deleted() = runTest {
        val cache = MemoryCache()
        val store = store(cache)
        self()
        message("2", "m1", "2", "Hi", 1_000)
        store.addPending("2", "local-1", "Are you there?")
        store.failPending("local-1")
        store.addPending("2", "local-2", "Still sending")
        store.addPending("2", "local-3", "photo.jpg", canRetry = false)
        store.failPending("local-3")
        advanceTimeBy(10_000)

        // A new start of the app: only the failed ones come back
        val restarted = store(cache)
        val bodies = restarted.messages("2").map { it.body to it.canRetry }
        assertEquals(listOf("Hi" to false, "Are you there?" to true, "photo.jpg" to false), bodies)

        val failed = assertNotNull(restarted.failedMessage("pending-local-1"))
        assertEquals("Are you there?", failed.text)
        restarted.removePending("pending-local-1")
        assertNull(restarted.failedMessage("pending-local-1"))
        assertEquals(listOf("Hi", "photo.jpg"), restarted.messages("2").map { it.body })
    }
}
