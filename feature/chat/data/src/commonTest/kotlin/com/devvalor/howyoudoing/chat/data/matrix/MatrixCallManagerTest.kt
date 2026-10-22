package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.data.matrix.dto.TurnServerDto
import com.devvalor.howyoudoing.chat.data.ondevice.MatrixAccountChats
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.CallEndReason
import com.devvalor.howyoudoing.chat.domain.CallMediaEngine
import com.devvalor.howyoudoing.chat.domain.CallMediaListener
import com.devvalor.howyoudoing.chat.domain.CallMediaSession
import com.devvalor.howyoudoing.chat.domain.CallPhase
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.IceCandidate
import com.devvalor.howyoudoing.chat.domain.IceServer
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.chat.data.matrix.dto.obj
import com.devvalor.howyoudoing.chat.data.matrix.dto.string
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MatrixCallManagerTest {

    private class Sent(val roomId: String, val type: String, val content: JsonObject)

    private class FakeTransport(override val userId: String?) : MatrixCallTransport {
        val events = MutableSharedFlow<MatrixCallEvent>(extraBufferCapacity = 16)
        override val callEvents: SharedFlow<MatrixCallEvent> = events
        val sent = mutableListOf<Sent>()
        override fun ensureStarted() = Unit
        override suspend fun sendCallEvent(roomId: String, type: String, content: JsonObject): EmptyResult<DataError.Remote> {
            sent += Sent(roomId, type, content)
            return Result.Success(Unit)
        }
        override suspend fun turnServer(): TurnServerDto = TurnServerDto(uris = listOf("turn:turn.example.org"), username = "u", password = "p")
    }

    private class FakeSession(val video: Boolean, val iceServers: List<IceServer>, val listener: CallMediaListener) : CallMediaSession {
        var offeredTo: String? = null
        var remoteAnswer: String? = null
        val remoteCandidates = mutableListOf<IceCandidate>()
        var closed = false
        override suspend fun createOffer() = if (video) "v=0 m=audio m=video" else "v=0 m=audio"
        override suspend fun answerOffer(offerSdp: String): String {
            offeredTo = offerSdp
            return "answer-sdp"
        }
        override suspend fun setRemoteAnswer(answerSdp: String) {
            remoteAnswer = answerSdp
        }
        override fun addRemoteCandidate(candidate: IceCandidate) {
            remoteCandidates += candidate
        }
        override fun setMuted(muted: Boolean) = Unit
        override fun setCameraEnabled(enabled: Boolean) = Unit
        override fun switchCamera() = Unit
        override fun setSpeakerOn(on: Boolean) = Unit
        override fun close() {
            closed = true
        }
    }

    private class FakeMedia : CallMediaEngine {
        override val isSupported = true
        val sessions = mutableListOf<FakeSession>()
        override fun createSession(iceServers: List<IceServer>, video: Boolean, listener: CallMediaListener) =
            FakeSession(video, iceServers, listener).also { sessions += it }
    }

    private class FakeChats : ChatRepository {
        override fun observeSyncState(): Flow<SyncState> = emptyFlow()
        override fun observeChats(): Flow<List<Chat>> = emptyFlow()
        override suspend fun refresh() = Unit
        override fun observeChat(chatId: String): Flow<Chat?> = flowOf(
            Chat(id = chatId, name = "Anna", network = ChatNetwork.MATRIX, lastMessage = null, unreadCount = 0, isGroup = false)
        )
        override fun observeMessages(chatId: String): Flow<List<Message>> = emptyFlow()
        override suspend fun loadOlderMessages(chatId: String): EmptyResult<DataError.Remote> = Result.Success(Unit)
        override fun hasMoreHistory(chatId: String): Flow<Boolean> = emptyFlow()
        override suspend fun sendMessage(chatId: String, body: String, replyToId: String?): EmptyResult<DataError.Remote> = Result.Success(Unit)
        override suspend fun sendAttachment(chatId: String, attachment: Attachment, caption: String?): EmptyResult<DataError.Remote> =
            Result.Success(Unit)
        override suspend fun toggleReaction(chatId: String, messageId: String, key: String): EmptyResult<DataError.Remote> = Result.Success(Unit)
        override suspend fun setTyping(chatId: String, isTyping: Boolean): EmptyResult<DataError.Remote> = Result.Success(Unit)
        override suspend fun markAsRead(chatId: String): EmptyResult<DataError.Remote> = Result.Success(Unit)
        override suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean): MediaRequest? = null
        override suspend fun downloadMedia(mxcUrl: String): ByteArray? = null
    }

    private val server = FakeTransport("@me:example.org")
    private val account = FakeTransport("@me:matrix.org")
    private val media = FakeMedia()

    private fun TestScope.manager() = MatrixCallManager(server, account, media, FakeChats(), backgroundScope).also { runCurrent() }

    private fun event(type: String, callId: String, sender: String = "@anna:example.org", party: String = "anna-phone", extra: JsonObjectBuilderBlock = {}) =
        MatrixCallEvent("!room:example.org", type, sender, buildJsonObject {
            put("call_id", callId)
            put("party_id", party)
            put("version", "1")
            extra()
        }, ageMillis = 0)

    private fun invite(callId: String, sdp: String = "v=0 m=audio m=video") = event("m.call.invite", callId) {
        put("lifetime", 60_000)
        putJsonObject("offer") {
            put("type", "offer")
            put("sdp", sdp)
        }
    }

    private suspend fun TestScope.receive(transport: FakeTransport, event: MatrixCallEvent) {
        transport.events.emit(event)
        runCurrent()
    }

    @Test
    fun incomingVideoCallRingsAndAnswerSendsTheAnswer() = runTest {
        val calls = manager()
        receive(server, invite("c1"))
        val ringing = calls.call.value!!
        assertEquals(CallPhase.RINGING, ringing.phase)
        assertTrue(ringing.isIncoming)
        assertTrue(ringing.isVideo)
        assertEquals("Anna", ringing.peerName)
        assertEquals("!room:example.org", ringing.chatId)

        // Candidates before answering wait for the peer connection
        receive(server, event("m.call.candidates", "c1") {
            putJsonArray("candidates") { addJsonObject { put("candidate", "candidate:1"); put("sdpMid", "0"); put("sdpMLineIndex", 0) } }
        })
        calls.answer(video = false)
        val session = media.sessions.single()
        assertEquals("v=0 m=audio m=video", session.offeredTo)
        assertEquals(false, session.video)
        assertEquals(listOf("turn:turn.example.org"), session.iceServers.single().urls)
        assertEquals(listOf(IceCandidate("candidate:1", "0", 0)), session.remoteCandidates)
        val answer = server.sent.single { it.type == "m.call.answer" }
        assertEquals("c1", answer.content.string("call_id"))
        assertEquals("answer-sdp", answer.content.obj("answer")?.string("sdp"))
        assertEquals(CallPhase.CONNECTING, calls.call.value?.phase)

        session.listener.onConnected()
        runCurrent()
        assertEquals(CallPhase.CONNECTED, calls.call.value?.phase)
    }

    @Test
    fun outgoingCallSelectsTheAnswerAndEndsOnRemoteHangup() = runTest {
        val calls = manager()
        assertTrue(calls.startCall(MatrixAccountChats.PREFIX + "!room:example.org", video = false) is Result.Success)
        // A Matrix account chat goes through the account's session
        val invite = account.sent.single()
        assertEquals("m.call.invite", invite.type)
        assertEquals("!room:example.org", invite.roomId)
        assertEquals("v=0 m=audio", invite.content.obj("offer")?.string("sdp"))
        val callId = invite.content.string("call_id")!!
        assertEquals(CallPhase.CALLING, calls.call.value?.phase)

        receive(account, event("m.call.answer", callId) { putJsonObject("answer") { put("type", "answer"); put("sdp", "their-answer") } })
        assertEquals("their-answer", media.sessions.single().remoteAnswer)
        val select = account.sent.single { it.type == "m.call.select_answer" }
        assertEquals("anna-phone", select.content.string("selected_party_id"))
        assertEquals(CallPhase.CONNECTING, calls.call.value?.phase)

        receive(account, event("m.call.hangup", callId) { put("reason", "user_hangup") })
        assertEquals(CallPhase.ENDED, calls.call.value?.phase)
        assertEquals(CallEndReason.REMOTE_HUNG_UP, calls.call.value?.endReason)
        assertTrue(media.sessions.single().closed)
        // The ended call stays a moment for the screen, then goes away
        advanceTimeBy(3_000)
        runCurrent()
        assertNull(calls.call.value)
    }

    @Test
    fun unansweredCallTimesOut() = runTest {
        val calls = manager()
        calls.startCall("!room:example.org", video = true)
        advanceTimeBy(60_001)
        runCurrent()
        val hangup = server.sent.single { it.type == "m.call.hangup" }
        assertEquals("invite_timeout", hangup.content.string("reason"))
        assertEquals(CallEndReason.NO_ANSWER, calls.call.value?.endReason)
    }

    @Test
    fun secondCallGetsBusy() = runTest {
        val calls = manager()
        receive(server, invite("c1"))
        receive(server, invite("c2"))
        val busy = server.sent.single()
        assertEquals("m.call.hangup", busy.type)
        assertEquals("c2", busy.content.string("call_id"))
        assertEquals("user_busy", busy.content.string("reason"))
        assertEquals("c1", calls.call.value?.callId)
    }

    @Test
    fun answeringOnAnotherOwnDeviceStopsRinging() = runTest {
        val calls = manager()
        receive(server, invite("c1"))
        receive(server, event("m.call.answer", "c1", sender = "@me:example.org", party = "my-laptop") {
            putJsonObject("answer") { put("type", "answer"); put("sdp", "x") }
        })
        assertEquals(CallEndReason.ANSWERED_ELSEWHERE, calls.call.value?.endReason)
        assertTrue(server.sent.isEmpty())
    }

    @Test
    fun declineSendsReject() = runTest {
        val calls = manager()
        receive(server, invite("c1", sdp = "v=0 m=audio"))
        calls.decline()
        assertEquals("m.call.reject", server.sent.single().type)
        assertEquals(CallEndReason.DECLINED, calls.call.value?.endReason)
    }

    @Test
    fun localCandidatesAreSentInBatches() = runTest {
        val calls = manager()
        calls.startCall("!room:example.org", video = false)
        val listener = media.sessions.single().listener
        listener.onLocalCandidate(IceCandidate("candidate:a", "0", 0))
        listener.onLocalCandidate(IceCandidate("candidate:b", "0", 0))
        advanceTimeBy(1_000)
        runCurrent()
        val batch = server.sent.single { it.type == "m.call.candidates" }
        assertEquals(2, (batch.content["candidates"] as JsonArray).size)
    }
}

private typealias JsonObjectBuilderBlock = kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
