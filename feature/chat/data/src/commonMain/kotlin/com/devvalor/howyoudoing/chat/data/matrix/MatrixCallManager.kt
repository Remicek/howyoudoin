package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.data.ondevice.MatrixAccountChats
import com.devvalor.howyoudoing.chat.domain.CallEndReason
import com.devvalor.howyoudoing.chat.domain.CallManager
import com.devvalor.howyoudoing.chat.domain.CallMediaEngine
import com.devvalor.howyoudoing.chat.domain.CallMediaListener
import com.devvalor.howyoudoing.chat.domain.CallMediaSession
import com.devvalor.howyoudoing.chat.domain.CallPhase
import com.devvalor.howyoudoing.chat.domain.CallState
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.IceCandidate
import com.devvalor.howyoudoing.chat.domain.IceServer
import com.devvalor.howyoudoing.chat.data.matrix.dto.obj
import com.devvalor.howyoudoing.chat.data.matrix.dto.string
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * One-to-one calls over Matrix VoIP (spec v1: m.call.invite / answer / select_answer / candidates / hangup /
 * reject) with the platform's WebRTC for media. Works with the app's own Matrix session (chat ID = room ID) and the
 * Matrix account connected as a network (chat ID = [MatrixAccountChats.PREFIX] + room ID).
 */
@OptIn(ExperimentalTime::class)
class MatrixCallManager(
    private val serverEngine: MatrixCallTransport,
    private val accountEngine: MatrixCallTransport,
    private val media: CallMediaEngine,
    private val chats: ChatRepository,
    private val scope: CoroutineScope
) : CallManager {

    override val isSupported: Boolean get() = media.isSupported

    private val _call = MutableStateFlow<CallState?>(null)
    override val call: StateFlow<CallState?> = _call.asStateFlow()

    /** Identifies this app among the user's devices in a call. */
    private val partyId = "howyoudoin-" + Random.nextLong(1, Long.MAX_VALUE).toString(36)

    private val lock = Mutex()
    private var active: ActiveCall? = null

    private inner class ActiveCall(
        val callId: String,
        val engine: MatrixCallTransport,
        val roomId: String,
        val isIncoming: Boolean,
        var offerSdp: String? = null
    ) {
        var session: CallMediaSession? = null
        var remotePartyId: String? = null
        /** Remote candidates that came before we had a peer connection. */
        val pendingRemoteCandidates = mutableListOf<IceCandidate>()
        /** Local candidates gathered but not sent yet (sent in batches). */
        val outgoingCandidates = mutableListOf<IceCandidate>()
        var candidateFlush: Job? = null
        var timeout: Job? = null
    }

    init {
        listOf(serverEngine, accountEngine).forEach { engine ->
            // Calls can ring before any screen shows the chats
            engine.ensureStarted()
            scope.launch { engine.callEvents.collect { event -> lock.withLock { handle(engine, event) } } }
        }
    }

    private fun chatIdFor(engine: MatrixCallTransport, roomId: String) =
        if (engine === accountEngine) MatrixAccountChats.PREFIX + roomId else roomId

    private fun engineFor(chatId: String): Pair<MatrixCallTransport, String> =
        if (chatId.startsWith(MatrixAccountChats.PREFIX)) accountEngine to chatId.removePrefix(MatrixAccountChats.PREFIX)
        else serverEngine to chatId

    override fun canCall(chat: Chat): Boolean = isSupported && chat.network == ChatNetwork.MATRIX && !chat.isGroup

    // --- local actions ---

    override suspend fun startCall(chatId: String, video: Boolean): EmptyResult<DataError.Remote> = lock.withLock {
        if (active != null) return@withLock Result.Failure(DataError.Remote.CONFLICT)
        val (engine, roomId) = engineFor(chatId)
        val callId = newId()
        val call = ActiveCall(callId, engine, roomId, isIncoming = false)
        active = call
        _call.value = CallState(callId, chatId, peerName(chatId), isVideo = video, isIncoming = false, phase = CallPhase.CALLING,
            isCameraOn = video, isSpeakerOn = video)
        try {
            val session = media.createSession(iceServers(engine), video, listenerFor(call))
            call.session = session
            session.setSpeakerOn(video)
            val offer = session.createOffer()
            val sent = engine.sendCallEvent(roomId, "m.call.invite", base(callId) {
                put("lifetime", INVITE_LIFETIME_MILLIS)
                putJsonObject("offer") {
                    put("type", "offer")
                    put("sdp", offer)
                }
            })
            if (sent is Result.Failure) {
                end(call, CallEndReason.FAILED)
                return@withLock sent
            }
            call.timeout = scope.launch {
                delay(INVITE_LIFETIME_MILLIS)
                lock.withLock {
                    if (active === call && _call.value?.phase == CallPhase.CALLING) {
                        sendHangup(call, "invite_timeout")
                        end(call, CallEndReason.NO_ANSWER)
                    }
                }
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            end(call, CallEndReason.FAILED)
            Result.Failure(DataError.Remote.UNKNOWN)
        }
    }

    override suspend fun answer(video: Boolean) = lock.withLock {
        val call = active ?: return@withLock
        val offer = call.offerSdp ?: return@withLock
        if (!call.isIncoming || _call.value?.phase != CallPhase.RINGING) return@withLock
        call.timeout?.cancel()
        val sendVideo = video && offer.hasVideo()
        _call.update { it?.copy(phase = CallPhase.CONNECTING, isCameraOn = sendVideo, isSpeakerOn = it.isVideo) }
        try {
            // Without our camera the remote video still arrives (the offer asks for it)
            val session = media.createSession(iceServers(call.engine), sendVideo, listenerFor(call))
            call.session = session
            session.setSpeakerOn(offer.hasVideo())
            val answer = session.answerOffer(offer)
            call.pendingRemoteCandidates.forEach(session::addRemoteCandidate)
            call.pendingRemoteCandidates.clear()
            val sent = call.engine.sendCallEvent(call.roomId, "m.call.answer", base(call.callId) {
                putJsonObject("answer") {
                    put("type", "answer")
                    put("sdp", answer)
                }
            })
            if (sent is Result.Failure) end(call, CallEndReason.FAILED)
        } catch (e: Exception) {
            sendHangup(call, "ice_failed")
            end(call, CallEndReason.FAILED)
        }
    }

    override suspend fun decline() = lock.withLock {
        val call = active ?: return@withLock
        if (!call.isIncoming || _call.value?.phase != CallPhase.RINGING) return@withLock
        call.engine.sendCallEvent(call.roomId, "m.call.reject", base(call.callId) {})
        end(call, CallEndReason.DECLINED)
    }

    override suspend fun hangUp() = lock.withLock {
        val call = active ?: return@withLock
        if (call.isIncoming && _call.value?.phase == CallPhase.RINGING) {
            call.engine.sendCallEvent(call.roomId, "m.call.reject", base(call.callId) {})
            end(call, CallEndReason.DECLINED)
        } else {
            sendHangup(call, "user_hangup")
            end(call, CallEndReason.HUNG_UP)
        }
    }

    override fun setMuted(muted: Boolean) {
        active?.session?.setMuted(muted)
        _call.update { it?.copy(isMuted = muted) }
    }

    override fun setCameraEnabled(enabled: Boolean) {
        active?.session?.setCameraEnabled(enabled)
        _call.update { it?.copy(isCameraOn = enabled) }
    }

    override fun switchCamera() {
        active?.session?.switchCamera()
    }

    override fun setSpeakerOn(on: Boolean) {
        active?.session?.setSpeakerOn(on)
        _call.update { it?.copy(isSpeakerOn = on) }
    }

    // --- remote events ---

    private suspend fun handle(engine: MatrixCallTransport, event: MatrixCallEvent) {
        val content = event.content
        val callId = content.string("call_id") ?: return
        val senderParty = content.string("party_id")
        val ownUserId = engine.userId
        // Our own echo
        if (event.sender == ownUserId && senderParty == partyId) return
        val call = active
        when (event.type) {
            "m.call.invite" -> {
                if (event.sender == ownUserId) return
                val offer = content.obj("offer")?.string("sdp") ?: return
                val lifetime = content.string("lifetime")?.toLongOrNull() ?: INVITE_LIFETIME_MILLIS
                if (event.ageMillis >= lifetime) return
                if (call != null) {
                    if (call.callId != callId) {
                        // One call at a time
                        engine.sendCallEvent(event.roomId, "m.call.hangup", base(callId) { put("reason", "user_busy") })
                    }
                    return
                }
                val chatId = chatIdFor(engine, event.roomId)
                val incoming = ActiveCall(callId, engine, event.roomId, isIncoming = true, offerSdp = offer)
                incoming.remotePartyId = senderParty
                active = incoming
                _call.value = CallState(callId, chatId, peerName(chatId), isVideo = offer.hasVideo(), isIncoming = true,
                    phase = CallPhase.RINGING)
                incoming.timeout = scope.launch {
                    delay(lifetime - event.ageMillis)
                    lock.withLock { if (active === incoming && _call.value?.phase == CallPhase.RINGING) end(incoming, CallEndReason.MISSED) }
                }
            }
            "m.call.answer" -> {
                if (call == null || call.callId != callId) return
                if (call.isIncoming) {
                    // Another of our devices answered
                    if (event.sender == ownUserId && _call.value?.phase == CallPhase.RINGING) end(call, CallEndReason.ANSWERED_ELSEWHERE)
                    return
                }
                if (call.remotePartyId != null) return
                val answer = content.obj("answer")?.string("sdp") ?: return
                call.remotePartyId = senderParty
                call.timeout?.cancel()
                _call.update { it?.copy(phase = CallPhase.CONNECTING) }
                try {
                    call.session?.setRemoteAnswer(answer)
                    call.pendingRemoteCandidates.forEach { call.session?.addRemoteCandidate(it) }
                    call.pendingRemoteCandidates.clear()
                    if (senderParty != null) {
                        engine.sendCallEvent(call.roomId, "m.call.select_answer", base(callId) { put("selected_party_id", senderParty) })
                    }
                } catch (e: Exception) {
                    sendHangup(call, "ice_failed")
                    end(call, CallEndReason.FAILED)
                }
            }
            "m.call.select_answer" -> {
                if (call == null || call.callId != callId || !call.isIncoming) return
                val selected = content.string("selected_party_id")
                if (selected != null && selected != partyId) end(call, CallEndReason.ANSWERED_ELSEWHERE)
            }
            "m.call.candidates" -> {
                if (call == null || call.callId != callId || event.sender == ownUserId) return
                if (call.remotePartyId != null && senderParty != null && senderParty != call.remotePartyId) return
                val candidates = (content["candidates"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val candidate = obj.string("candidate")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    IceCandidate(candidate, obj.string("sdpMid"), obj["sdpMLineIndex"]?.jsonPrimitive?.intOrNull)
                }
                val session = call.session
                if (session == null) call.pendingRemoteCandidates += candidates else candidates.forEach(session::addRemoteCandidate)
            }
            "m.call.hangup", "m.call.reject" -> {
                if (call == null || call.callId != callId) return
                if (event.sender == ownUserId) {
                    // Declined or ended on another of our devices
                    if (call.isIncoming) end(call, CallEndReason.ANSWERED_ELSEWHERE)
                    return
                }
                val phase = _call.value?.phase
                val reason = when {
                    content.string("reason") == "user_busy" -> CallEndReason.BUSY
                    event.type == "m.call.reject" || (phase == CallPhase.CALLING) -> CallEndReason.DECLINED
                    phase == CallPhase.RINGING -> CallEndReason.MISSED
                    else -> CallEndReason.REMOTE_HUNG_UP
                }
                end(call, reason)
            }
        }
    }

    // --- helpers ---

    private fun listenerFor(call: ActiveCall) = object : CallMediaListener {
        override fun onLocalCandidate(candidate: IceCandidate) {
            scope.launch {
                lock.withLock {
                    if (active !== call) return@withLock
                    call.outgoingCandidates += candidate
                    if (call.candidateFlush?.isActive != true) {
                        call.candidateFlush = scope.launch {
                            delay(CANDIDATE_BATCH_MILLIS)
                            flushCandidates(call)
                        }
                    }
                }
            }
        }

        override fun onConnected() {
            scope.launch {
                lock.withLock {
                    if (active === call) {
                        _call.update { it?.copy(phase = CallPhase.CONNECTED, connectedAtMillis = it.connectedAtMillis ?: now()) }
                    }
                }
            }
        }

        override fun onFailed() {
            scope.launch {
                lock.withLock {
                    if (active === call) {
                        sendHangup(call, "ice_failed")
                        end(call, CallEndReason.FAILED)
                    }
                }
            }
        }
    }

    private suspend fun flushCandidates(call: ActiveCall) {
        val batch = lock.withLock {
            if (active !== call) return
            call.outgoingCandidates.toList().also { call.outgoingCandidates.clear() }
        }
        if (batch.isEmpty()) return
        call.engine.sendCallEvent(call.roomId, "m.call.candidates", base(call.callId) {
            putJsonArray("candidates") {
                batch.forEach { c ->
                    addJsonObject {
                        put("candidate", c.candidate)
                        c.sdpMid?.let { put("sdpMid", it) }
                        c.sdpMLineIndex?.let { put("sdpMLineIndex", it) }
                    }
                }
            }
        })
    }

    private suspend fun sendHangup(call: ActiveCall, reason: String) {
        call.engine.sendCallEvent(call.roomId, "m.call.hangup", base(call.callId) { put("reason", reason) })
    }

    /** Ends the call locally; the screen shows the outcome briefly before the call goes away. */
    private fun end(call: ActiveCall, reason: CallEndReason) {
        if (active !== call) return
        active = null
        call.timeout?.cancel()
        call.candidateFlush?.cancel()
        runCatching { call.session?.close() }
        _call.update { it?.copy(phase = CallPhase.ENDED, endReason = reason) }
        scope.launch {
            delay(ENDED_LINGER_MILLIS)
            _call.update { current -> if (current?.callId == call.callId && current.phase == CallPhase.ENDED) null else current }
        }
    }

    private suspend fun iceServers(engine: MatrixCallTransport): List<IceServer> {
        val turn = withTimeoutOrNull(TURN_TIMEOUT_MILLIS) { engine.turnServer() } ?: return emptyList()
        if (turn.uris.isEmpty()) return emptyList()
        return listOf(IceServer(turn.uris, turn.username, turn.password))
    }

    private suspend fun peerName(chatId: String): String =
        withTimeoutOrNull(NAME_TIMEOUT_MILLIS) { chats.observeChat(chatId).first { it != null } }?.name ?: "Matrix"

    private fun base(callId: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("call_id", callId)
        put("party_id", partyId)
        put("version", "1")
        extra()
    }

    private fun String.hasVideo() = contains("m=video")

    private fun newId() = "hyd" + Random.nextLong(1, Long.MAX_VALUE).toString(36) + now().toString(36)

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val INVITE_LIFETIME_MILLIS = 60_000L
        const val CANDIDATE_BATCH_MILLIS = 300L
        const val ENDED_LINGER_MILLIS = 2_500L
        const val TURN_TIMEOUT_MILLIS = 5_000L
        const val NAME_TIMEOUT_MILLIS = 2_000L
    }
}
