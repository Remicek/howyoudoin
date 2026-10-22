package com.devvalor.howyoudoing.app.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.devvalor.howyoudoing.chat.domain.CallEndReason
import com.devvalor.howyoudoing.chat.domain.CallManager
import com.devvalor.howyoudoing.chat.domain.CallPhase
import com.devvalor.howyoudoing.chat.domain.CallState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** The call screen: ringing, calling, the call itself. Starting or answering asks for the microphone (and camera). */
class CallActivity : ComponentActivity() {

    private val callManager: CallManager by inject()
    private val media: AndroidCallMediaEngine by inject()

    /** What to do once the permissions are answered. */
    private var afterPermissions: ((audio: Boolean, camera: Boolean) -> Unit)? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val audio = result[Manifest.permission.RECORD_AUDIO] ?: isGranted(Manifest.permission.RECORD_AUDIO)
        val camera = result[Manifest.permission.CAMERA] ?: isGranted(Manifest.permission.CAMERA)
        afterPermissions?.invoke(audio, camera)
        afterPermissions = null
    }

    /** Set once a call was shown, so the screen closes when it goes away (not before it started). */
    private val hadCall = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handle(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val call by callManager.call.collectAsState()
                val session by media.current.collectAsState()
                val remote by (session?.remoteVideo ?: flowOf(null)).collectAsState(null)
                val local by (session?.localVideo ?: flowOf(null)).collectAsState(null)
                LaunchedEffect(call == null) {
                    if (call != null) hadCall.value = true
                    else if (hadCall.value) finish()
                }
                call?.let { state ->
                    CallScreen(
                        call = state,
                        eglBase = media.eglBase,
                        remoteVideo = remote,
                        localVideo = local,
                        onAnswer = { video -> answer(video) },
                        onDecline = { lifecycleScope.launch { callManager.decline() } },
                        onHangUp = { lifecycleScope.launch { callManager.hangUp() } },
                        onMute = { callManager.setMuted(!state.isMuted) },
                        onSpeaker = { callManager.setSpeakerOn(!state.isSpeakerOn) },
                        onCamera = { toggleCamera(state) },
                        onSwitchCamera = callManager::switchCamera
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val chatId = intent.getStringExtra(EXTRA_CHAT_ID)
        when {
            chatId != null -> {
                val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
                // Only once, not again when the activity is recreated
                intent.removeExtra(EXTRA_CHAT_ID)
                withPermissions(video) { audio, camera ->
                    if (!audio) {
                        Toast.makeText(this, "Calls need the microphone", Toast.LENGTH_LONG).show()
                        finish()
                        return@withPermissions
                    }
                    lifecycleScope.launch {
                        if (callManager.startCall(chatId, video && camera) is com.devvalor.howyoudoing.core.domain.util.Result.Failure) {
                            Toast.makeText(this@CallActivity, "Couldn't start the call", Toast.LENGTH_LONG).show()
                            if (callManager.call.value == null) finish()
                        }
                    }
                }
            }
            intent.action == ACTION_ANSWER -> {
                intent.action = null
                answer(callManager.call.value?.isVideo == true)
            }
        }
    }

    private fun answer(video: Boolean) {
        withPermissions(video) { audio, camera ->
            if (!audio) {
                Toast.makeText(this, "Answering needs the microphone", Toast.LENGTH_LONG).show()
                return@withPermissions
            }
            lifecycleScope.launch { callManager.answer(video && camera) }
        }
    }

    private fun toggleCamera(state: CallState) {
        if (state.isCameraOn || isGranted(Manifest.permission.CAMERA)) callManager.setCameraEnabled(!state.isCameraOn)
    }

    private fun withPermissions(video: Boolean, then: (audio: Boolean, camera: Boolean) -> Unit) {
        val wanted = listOfNotNull(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA.takeIf { video })
        val missing = wanted.filterNot(::isGranted)
        if (missing.isEmpty()) {
            then(true, isGranted(Manifest.permission.CAMERA))
        } else {
            afterPermissions = then
            permissions.launch(missing.toTypedArray())
        }
    }

    private fun isGranted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_ANSWER = "com.devvalor.howyoudoing.call.ANSWER"
        private const val EXTRA_CHAT_ID = "chatId"
        private const val EXTRA_VIDEO = "video"

        fun intent(context: Context) = Intent(context, CallActivity::class.java)

        /** Opens the screen and starts calling [chatId]. */
        fun startCall(context: Context, chatId: String, video: Boolean) {
            context.startActivity(
                intent(context).putExtra(EXTRA_CHAT_ID, chatId).putExtra(EXTRA_VIDEO, video).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
private fun CallScreen(
    call: CallState,
    eglBase: EglBase,
    remoteVideo: VideoTrack?,
    localVideo: VideoTrack?,
    onAnswer: (video: Boolean) -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onCamera: () -> Unit,
    onSwitchCamera: () -> Unit
) {
    val showRemote = call.isVideo && remoteVideo != null && call.phase == CallPhase.CONNECTED
    Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        if (showRemote) {
            VideoView(remoteVideo!!, eglBase, mirror = false, overlay = false, modifier = Modifier.fillMaxSize())
        }
        if (localVideo != null && call.isCameraOn && call.phase != CallPhase.ENDED) {
            VideoView(
                localVideo, eglBase, mirror = true, overlay = true,
                modifier = Modifier.safeDrawingPadding().padding(16.dp).align(Alignment.TopEnd)
                    .size(width = 110.dp, height = 160.dp).clip(RoundedCornerShape(12.dp))
            )
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(if (showRemote) 8.dp else 96.dp))
            if (!showRemote) {
                Box(
                    Modifier.size(112.dp).clip(CircleShape).background(Color(0xFF0DBD8B)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(initials(call.peerName), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(20.dp))
            }
            Text(call.peerName, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(statusText(call), color = Color(0xFFB8C2CC), fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            when {
                call.phase == CallPhase.RINGING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(Icons.Default.CallEnd, "Decline", Color(0xFFE5484D), onDecline)
                    RoundButton(Icons.Default.Call, "Answer", Color(0xFF30A46C)) { onAnswer(false) }
                    if (call.isVideo) RoundButton(Icons.Default.Videocam, "Video", Color(0xFF30A46C)) { onAnswer(true) }
                }
                call.phase != CallPhase.ENDED -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToggleButton(if (call.isMuted) Icons.Default.MicOff else Icons.Default.Mic, "Mute", call.isMuted, onMute)
                    ToggleButton(if (call.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.PhoneInTalk, "Speaker", call.isSpeakerOn, onSpeaker)
                    if (call.isVideo) {
                        ToggleButton(if (call.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff, "Camera", !call.isCameraOn, onCamera)
                        if (call.isCameraOn) ToggleButton(Icons.Default.Cameraswitch, "Flip", false, onSwitchCamera)
                    }
                    RoundButton(Icons.Default.CallEnd, "Hang up", Color(0xFFE5484D), onHangUp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun statusText(call: CallState): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) {
        while (call.phase == CallPhase.CONNECTED) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return when (call.phase) {
        CallPhase.RINGING -> if (call.isVideo) "Incoming video call" else "Incoming call"
        CallPhase.CALLING -> "Calling…"
        CallPhase.CONNECTING -> "Connecting…"
        CallPhase.CONNECTED -> {
            val seconds = ((now - (call.connectedAtMillis ?: now)) / 1000).coerceAtLeast(0)
            "%d:%02d".format(seconds / 60, seconds % 60)
        }
        CallPhase.ENDED -> when (call.endReason) {
            CallEndReason.DECLINED -> "Declined"
            CallEndReason.MISSED -> "Missed call"
            CallEndReason.NO_ANSWER -> "No answer"
            CallEndReason.BUSY -> "Busy"
            CallEndReason.ANSWERED_ELSEWHERE -> "Answered on another device"
            CallEndReason.FAILED -> "Call failed"
            else -> "Call ended"
        }
    }
}

private fun initials(name: String) =
    name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

@Composable
private fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(68.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = color, contentColor = Color.White)
        ) { Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (active) Color.White else Color(0x33FFFFFF),
                contentColor = if (active) Color.Black else Color.White
            )
        ) { Icon(icon, contentDescription = label) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

/** A WebRTC video track drawn in a SurfaceView; [overlay] puts it above another video. */
@Composable
private fun VideoView(track: VideoTrack, eglBase: EglBase, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    // The track drawn by the renderer, swapped when the call's track changes
    val attached = remember { arrayOfNulls<VideoTrack>(1) }
    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglBase.eglBaseContext, null)
                setMirror(mirror)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                if (overlay) setZOrderMediaOverlay(true)
            }
        },
        update = { renderer ->
            if (attached[0] !== track) {
                attached[0]?.let { runCatching { it.removeSink(renderer) } }
                runCatching { track.addSink(renderer) }
                attached[0] = track
            }
        },
        onRelease = { renderer ->
            attached[0]?.let { runCatching { it.removeSink(renderer) } }
            renderer.release()
        },
        modifier = modifier
    )
}
