package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.chat.domain.CallInfo
import com.devvalor.howyoudoing.chat.domain.CallOutcome
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.core.domain.media.MessageRef
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
        override suspend fun sendText(threadKey: String, text: String, isEncrypted: Boolean, isGroup: Boolean) =
            Result.Success("sent")

        override suspend fun historyStatus(): Result<OnDeviceHistoryStatus, DataError.Remote> =
            Result.Success(OnDeviceHistoryStatus(isRestored = false, isBackupAvailable = false))

        override suspend fun restoreHistory(pin: String): EmptyResult<OnDeviceHistoryError> = Result.Success(Unit)
        override suspend fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long) =
            Result.Success(Unit)

        override suspend fun requestOlder(
            threadKey: String,
            beforeTimestampMs: Long,
            beforeMessageId: String,
            beforeMessageIsOutgoing: Boolean
        ) =
            Result.Success(Unit)

        override suspend fun sendReaction(
            threadKey: String,
            message: MessageRef,
            emoji: String,
            isEncrypted: Boolean,
            isGroup: Boolean
        ) = Result.Success(Unit)

        override suspend fun markRead(
            threadKey: String,
            isEncrypted: Boolean,
            isGroup: Boolean,
            lastMessageId: String,
            senderId: String
        ) =
            Result.Success(Unit)

        override suspend fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean) =
            Result.Success(Unit)

        override suspend fun unlink() = Unit
    }

    private class MemoryCache : OnDeviceInboxCache {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) {
            this.value = value
        }

        override fun clear() {
            value = null
        }
    }

    private val client = FakeClient()

    private fun TestScope.store(cache: OnDeviceInboxCache = MemoryCache()) =
        OnDeviceMessengerStore(
            client,
            cache,
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        )

    private suspend fun emit(json: String) = client.events.emit(json)

    private suspend fun self() = emit("""{"type":"self","id":"1"}""")
    private suspend fun contact(id: String, name: String, avatar: String = "") =
        emit(
            """{"type":"contact","id":"$id","name":"$name","firstName":"${name.substringBefore(' ')}",""" +
                """"avatarUrl":"$avatar"}"""
        )

    // Mirrors the fields of hydbridge's message event
    @Suppress("LongParameterList")
    private suspend fun message(
        thread: String,
        id: String,
        sender: String,
        text: String,
        ts: Long,
        kind: String = "text",
        extra: String = ""
    ) =
        emit(
            """{"type":"message","threadKey":"$thread","id":"$id","otid":"$id","senderId":"$sender","text":"$text",""" +
                """"kind":"$kind","timestampMs":$ts,"isEncrypted":true$extra}"""
        )

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
    fun a_chat_started_in_the_app_moves_to_the_one_the_network_created() = runTest {
        val store = store()
        self()
        contact("2", "Anna Nowak")
        val started = store.startDirectChat("new:2", "2", "Anna Nowak")
        store.addPending("new:2", "local-1", "Hi Anna")

        emit("""{"type":"moved","from":"new:2","to":"conv-9"}""")

        assertEquals("conv-9", store.snapshot.value.currentKey("new:2"))
        assertEquals("fbdevice:new:2", started)
        val chat = store.chats().single()
        assertEquals("fbdevice:conv-9", chat.id)
        assertEquals("Anna Nowak", chat.name)
        assertEquals(listOf("Hi Anna"), store.messages("conv-9").map { it.body })
    }

        @Test
    fun whatsapp_group_known_only_from_messages_is_a_group() = runTest {
        val store = store()
        self()
        message("123@g.us", "m1", "2", "Hello group", 1_000)

        assertTrue(store.chats().single().isGroup)
    }

    @Test
    fun a_ringing_call_becomes_a_missed_call_in_the_same_message() = runTest {
        val store = store()
        self()
        message("2", "m1", "2", "Hi", 1_000)
        message(
            "2", "call-1", "2", "📞 Incoming video call", 2_000, "call",
            ""","callState":"incoming","isVideo":true"""
        )

        val ringing = store.chats().single().lastMessage
        assertEquals(MessageType.CALL, ringing?.type)
        assertEquals(CallInfo(CallOutcome.INCOMING, isVideo = true), ringing?.call)

        message(
            "2", "call-1", "2", "📞 Video call (1:15)", 2_000, "call",
            ""","callState":"ended","isVideo":true,"durationMs":75000"""
        )
        val ended = store.messages("2").last()
        assertEquals(2, store.messages("2").size)
        assertEquals(CallInfo(CallOutcome.ENDED, isVideo = true, durationMillis = 75_000), ended.call)
    }

    @Test
    fun a_call_log_repeated_without_its_card_stays_a_call() = runTest {
        val store = store()
        self()
        message("2", "m9", "2", "📞 Missed call", 2_000, "call", ""","callState":"missed"""")
        message("2", "m9", "2", "", 2_000)

        val call = store.messages("2").single()
        assertEquals(MessageType.CALL, call.type)
        assertEquals(CallOutcome.MISSED, call.call?.state)
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
        emit(
            """{"type":"attachment","threadKey":"2","messageId":"m1","kind":"image","url":"https://cdn/x.jpg",""" +
                """"previewUrl":"https://cdn/x.jpg"}"""
        )
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
        emit(
            """{"type":"thread","key":"$key","name":"Anna","isGroup":false,"isEncrypted":false,""" +
                """"lastActivityMs":$lastActivity,"lastReadMs":$lastRead}"""
        )

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

    private fun TestScope.whatsAppStore() = OnDeviceMessengerStore(
        client,
        MemoryCache(),
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        OnDeviceMessengerStore.WHATSAPP_CHAT_ID_PREFIX
    )

    private suspend fun attachment(thread: String, id: String, kind: String) = emit(
        """{"type":"attachment","threadKey":"$thread","messageId":"$id","kind":"$kind","url":"file:///m/$id"}"""
    )

    @Test
    fun whatsapp_album_announcements_are_hidden_and_replies_to_them_quote_the_first_photo() = runTest {
        val store = whatsAppStore()
        self()
        contact("2", "Anna Nowak")
        message("g@g.us", "album", "2", "", 1_000, kind = "album")
        message("g@g.us", "p1", "2", "", 1_100, kind = "image")
        attachment("g@g.us", "p1", "image")
        message("g@g.us", "p2", "2", "", 1_200, kind = "image")
        attachment("g@g.us", "p2", "image")
        message("g@g.us", "r", "1", "Nice", 5_000, extra = ""","replyToId":"album"""")

        val messages = store.messages("g@g.us")
        assertEquals(listOf("p1", "p2", "r"), messages.map { it.id })
        val reply = assertNotNull(messages.last().replyTo)
        assertEquals("p1", reply.messageId)
        assertEquals("📷 Photo", reply.body)
    }

    @Test
    fun whatsapp_albums_cached_as_unknown_messages_are_recognised_too() = runTest {
        val store = whatsAppStore()
        self()
        message("g@g.us", "album", "2", "", 1_000, kind = "other")
        message("g@g.us", "p1", "2", "", 1_500, kind = "image")
        attachment("g@g.us", "p1", "image")

        assertEquals(listOf("p1"), store.messages("g@g.us").map { it.id })
    }

    @Test
    fun cached_whatsapp_hd_copies_that_could_not_download_are_hidden() = runTest {
        val store = whatsAppStore()
        self()
        message("g@g.us", "v1", "2", "", 1_000, kind = "video")
        attachment("g@g.us", "v1", "video")
        // The HD copy, cached before HD copies were recognised: no file and nothing to download it with
        message("g@g.us", "v1hd", "2", "", 20_000, kind = "video")
        // A video sent much later that failed to download stays
        message("g@g.us", "v2", "2", "", 900_000, kind = "video")

        assertEquals(listOf("v1", "v2"), store.messages("g@g.us").map { it.id })
    }

    @Test
    fun whatsapp_media_that_failed_to_download_can_still_be_downloaded_and_videos_have_their_preview() = runTest {
        val store = whatsAppStore()
        self()
        message("2@s.whatsapp.net", "p1", "2", "", 1_000, kind = "image", extra = ""","mediaRef":"cmVm"""")
        message(
            "2@s.whatsapp.net", "v1", "2", "", 2_000, kind = "video",
            extra = ""","mediaRef":"cmVm","thumbnailUrl":"file:///m/v1.jpg","durationMs":13000"""
        )

        val (photo, video) = store.messages("2@s.whatsapp.net")
        assertEquals(MessageType.IMAGE, photo.type)
        assertEquals(OnDeviceMessengerStore.onRequestMediaUrl("wadevice:2@s.whatsapp.net", "p1"), photo.media?.url)
        assertEquals(MessageType.VIDEO, video.type)
        assertEquals("file:///m/v1.jpg", video.media?.thumbnailUrl)
        assertEquals(13_000L, video.media?.durationMillis)
    }

    @Test
    fun messenger_videos_use_the_preview_picture_sent_with_them_also_when_cached_without_it() = runTest {
        val store = store()
        self()
        message("2", "v1", "2", "", 1_000, kind = "video")
        // Cached before previews were kept
        emit("""{"type":"attachment","threadKey":"2","messageId":"v1","kind":"video","url":"https://cdn/v.mp4"}""")
        assertNull(store.messages("2").single().media?.thumbnailUrl)

        emit(
            """{"type":"attachment","threadKey":"2","messageId":"v1","kind":"video","url":"https://cdn/v.mp4",""" +
                """"previewUrl":"https://cdn/v.jpg"}"""
        )
        assertEquals("https://cdn/v.jpg", store.messages("2").single().media?.thumbnailUrl)
    }

    @Test
    fun a_video_not_downloaded_yet_shows_in_its_shape_and_gets_its_preview_picture_later() = runTest {
        val store = store()
        self()
        message("2", "v1", "2", "", 1_000, kind = "video", extra = ""","mediaRef":"doc","width":480,"height":854""")
        val media = store.messages("2").single().media
        assertEquals(480 to 854, media?.width to media?.height)
        assertNull(media?.thumbnailUrl)

        emit("""{"type":"thumbnail","threadKey":"2","messageId":"v1","url":"file:///m/v1-thumb"}""")
        assertEquals("file:///m/v1-thumb", store.messages("2").single().media?.thumbnailUrl)
    }
}
