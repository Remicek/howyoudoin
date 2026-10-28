package com.devvalor.howyoudoing.chat.domain

import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import kotlinx.coroutines.flow.StateFlow

/** One-to-one audio and video calls with Matrix contacts (Matrix VoIP, WebRTC media). One call at a time. */
interface CallManager {
    /** False where there's no WebRTC (iOS for now). */
    val isSupported: Boolean

    /** The current call, or null. An ended call stays briefly with [CallPhase.ENDED] so the screen can say so. */
    val call: StateFlow<CallState?>

    /** Whether [chatId] can be called: a one-to-one Matrix chat. */
    fun canCall(chat: Chat): Boolean

    suspend fun startCall(chatId: String, video: Boolean): EmptyResult<DataError.Remote>

    /** Answers the ringing call; [video] sends our camera too (only if the caller offered video). */
    suspend fun answer(video: Boolean)
    suspend fun decline()
    suspend fun hangUp()
    fun setMuted(muted: Boolean)
    fun setCameraEnabled(enabled: Boolean)
    fun switchCamera()
    fun setSpeakerOn(on: Boolean)
}

data class CallState(
    val callId: String,
    val chatId: String,
    val peerName: String,
    val isVideo: Boolean,
    val isIncoming: Boolean,
    val phase: CallPhase,
    val isMuted: Boolean = false,
    val isCameraOn: Boolean = false,
    val isSpeakerOn: Boolean = false,
    /** When the call connected (epoch ms), for the duration shown. */
    val connectedAtMillis: Long? = null,
    val endReason: CallEndReason? = null
)

enum class CallPhase {
    /** Incoming, not answered yet. */
    RINGING,

    /** Outgoing, the other side hasn't answered yet. */
    CALLING,

    /** Answered; the media connection is being set up. */
    CONNECTING,
    CONNECTED,
    ENDED
}

enum class CallEndReason { HUNG_UP, REMOTE_HUNG_UP, DECLINED, MISSED, NO_ANSWER, ANSWERED_ELSEWHERE, BUSY, FAILED }

/** A STUN/TURN server for the media connection. */
data class IceServer(val urls: List<String>, val username: String? = null, val password: String? = null)

data class IceCandidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?)

/** The platform's WebRTC. */
interface CallMediaEngine {
    val isSupported: Boolean
    fun createSession(iceServers: List<IceServer>, video: Boolean, listener: CallMediaListener): CallMediaSession
}

interface CallMediaListener {
    fun onLocalCandidate(candidate: IceCandidate)
    fun onConnected()

    /** The media connection failed or was lost for good. */
    fun onFailed()
}

/** One call's peer connection, camera, microphone and audio routing. */
interface CallMediaSession {
    /** Creates the offer for an outgoing call (local description set); returns its SDP. */
    suspend fun createOffer(): String

    /** Takes the caller's offer and returns our answer's SDP. */
    suspend fun answerOffer(offerSdp: String): String
    suspend fun setRemoteAnswer(answerSdp: String)
    fun addRemoteCandidate(candidate: IceCandidate)
    fun setMuted(muted: Boolean)
    fun setCameraEnabled(enabled: Boolean)
    fun switchCamera()
    fun setSpeakerOn(on: Boolean)
    fun close()
}

/** Where WebRTC isn't available. */
object NoCallMediaEngine : CallMediaEngine {
    override val isSupported = false
    override fun createSession(
        iceServers: List<IceServer>,
        video: Boolean,
        listener: CallMediaListener
    ): CallMediaSession =
        throw UnsupportedOperationException("Calls aren't supported here")
}

/** Opens the platform's call screen, which asks for the microphone and camera and then calls. */
fun interface CallLauncher {
    fun call(chatId: String, video: Boolean)
}
