package com.devvalor.howyoudoing.app.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.devvalor.howyoudoing.chat.domain.CallMediaEngine
import com.devvalor.howyoudoing.chat.domain.CallMediaListener
import com.devvalor.howyoudoing.chat.domain.CallMediaSession
import com.devvalor.howyoudoing.chat.domain.IceCandidate
import com.devvalor.howyoudoing.chat.domain.IceServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** WebRTC (libwebrtc) for calls. The call screen draws [current]'s video tracks with [eglBase]. */
class AndroidCallMediaEngine(context: Context) : CallMediaEngine {

    private val context = context.applicationContext
    override val isSupported = true

    val eglBase: EglBase by lazy { EglBase.create() }

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this.context).createInitializationOptions())
        val audio = JavaAudioDeviceModule.builder(this.context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private val _current = MutableStateFlow<Session?>(null)
    /** The running call's media, for the call screen. */
    val current: StateFlow<Session?> = _current.asStateFlow()

    override fun createSession(iceServers: List<IceServer>, video: Boolean, listener: CallMediaListener): CallMediaSession =
        Session(iceServers, video, listener).also { _current.value = it }

    inner class Session(iceServers: List<IceServer>, private val video: Boolean, private val listener: CallMediaListener) :
        CallMediaSession {

        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val previousAudioMode = audioManager.mode

        private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
        val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()
        private val _localVideo = MutableStateFlow<VideoTrack?>(null)
        val localVideo: StateFlow<VideoTrack?> = _localVideo.asStateFlow()

        private var connected = false
        private val audioSource = factory.createAudioSource(MediaConstraints())
        private val audioTrack: AudioTrack = factory.createAudioTrack("hyd-audio", audioSource)
        private var capturer: CameraVideoCapturer? = null
        private var videoSource: VideoSource? = null
        private var textureHelper: SurfaceTextureHelper? = null
        private var cameraRunning = false

        private val peer: PeerConnection = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers.map { server ->
                PeerConnection.IceServer.builder(server.urls)
                    .apply {
                        server.username?.let(::setUsername)
                        server.password?.let(::setPassword)
                    }
                    .createIceServer()
            }).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            },
            Observer()
        ) ?: throw IllegalStateException("Couldn't create the connection")

        init {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            peer.addTrack(audioTrack, listOf(STREAM_ID))
            if (video) startCamera()
        }

        private fun startCamera() {
            val enumerator = Camera2Enumerator(context)
            val name = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing) ?: enumerator.deviceNames.firstOrNull() ?: return
            val cameraCapturer = enumerator.createCapturer(name, null) ?: return
            val helper = SurfaceTextureHelper.create("hyd-camera", eglBase.eglBaseContext)
            val source = factory.createVideoSource(false)
            cameraCapturer.initialize(helper, context, source.capturerObserver)
            cameraCapturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
            val track = factory.createVideoTrack("hyd-video", source)
            peer.addTrack(track, listOf(STREAM_ID))
            capturer = cameraCapturer
            videoSource = source
            textureHelper = helper
            cameraRunning = true
            _localVideo.value = track
        }

        private fun constraints() = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", video.toString())
        }

        override suspend fun createOffer(): String {
            val offer = sdp { peer.createOffer(it, constraints()) }
            set { peer.setLocalDescription(it, offer) }
            return offer.description
        }

        override suspend fun answerOffer(offerSdp: String): String {
            set { peer.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, offerSdp)) }
            val answer = sdp { peer.createAnswer(it, constraints()) }
            set { peer.setLocalDescription(it, answer) }
            return answer.description
        }

        override suspend fun setRemoteAnswer(answerSdp: String) {
            set { peer.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answerSdp)) }
        }

        override fun addRemoteCandidate(candidate: IceCandidate) {
            peer.addIceCandidate(org.webrtc.IceCandidate(candidate.sdpMid.orEmpty(), candidate.sdpMLineIndex ?: 0, candidate.candidate))
        }

        override fun setMuted(muted: Boolean) {
            audioTrack.setEnabled(!muted)
        }

        override fun setCameraEnabled(enabled: Boolean) {
            _localVideo.value?.setEnabled(enabled)
            val camera = capturer ?: return
            if (enabled && !cameraRunning) camera.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
            if (!enabled && cameraRunning) runCatching { camera.stopCapture() }
            cameraRunning = enabled
        }

        override fun switchCamera() {
            capturer?.switchCamera(null)
        }

        override fun setSpeakerOn(on: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wanted = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == wanted }
                if (device != null) audioManager.setCommunicationDevice(device) else audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = on
            }
        }

        override fun close() {
            if (_current.value === this) _current.value = null
            _remoteVideo.value = null
            _localVideo.value = null
            runCatching { capturer?.stopCapture() }
            capturer?.dispose()
            videoSource?.dispose()
            textureHelper?.dispose()
            peer.dispose()
            audioSource.dispose()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            audioManager.mode = previousAudioMode
        }

        private inner class Observer : PeerConnection.Observer {
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                listener.onLocalCandidate(IceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> if (!connected) {
                        connected = true
                        listener.onConnected()
                    }
                    PeerConnection.IceConnectionState.FAILED -> listener.onFailed()
                    else -> Unit
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                (transceiver.receiver.track() as? VideoTrack)?.let { _remoteVideo.value = it }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                (receiver.track() as? VideoTrack)?.let { _remoteVideo.value = it }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
        }
    }

    private suspend fun sdp(create: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { continuation ->
        create(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
            override fun onCreateFailure(error: String?) = continuation.resumeWithException(IllegalStateException(error))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        })
    }

    private suspend fun set(apply: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        apply(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetSuccess() = continuation.resume(Unit)
            override fun onSetFailure(error: String?) = continuation.resumeWithException(IllegalStateException(error))
        })
    }

    private companion object {
        const val STREAM_ID = "hyd-stream"
        const val CAPTURE_WIDTH = 1280
        const val CAPTURE_HEIGHT = 720
        const val CAPTURE_FPS = 30
    }
}
