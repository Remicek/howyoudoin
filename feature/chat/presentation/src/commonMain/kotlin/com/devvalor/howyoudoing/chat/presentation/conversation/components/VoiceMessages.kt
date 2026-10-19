package com.devvalor.howyoudoing.chat.presentation.conversation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.presentation.platform.VoiceFormat
import com.devvalor.howyoudoing.chat.presentation.platform.rememberMicrophonePermission
import com.devvalor.howyoudoing.chat.presentation.platform.rememberVoicePlayer
import com.devvalor.howyoudoing.chat.presentation.platform.rememberVoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.devvalor.howyoudoing.chat.domain.MediaInfo
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing

/** A voice message being recorded in the composer. */
data class VoiceRecordingUi(val elapsedMillis: Long, val level: Int)

/** What the conversation's single voice player is doing, and how bubbles start or stop it. */
class VoicePlayback(
    val playingId: String?,
    val loadingId: String?,
    val positionMillis: Long,
    val durationMillis: Long,
    val toggle: (messageId: String, media: MediaInfo) -> Unit,
)

/** Provided by the conversation screen where audio can play in the app; bubbles fall back to a file card. */
val LocalVoicePlayback = compositionLocalOf<VoicePlayback?> { null }

/** An audio message as a voice note: play / pause, progress and length. */
@Composable
fun VoiceNote(messageId: String, media: MediaInfo, playback: VoicePlayback, contentColor: Color) {
    val isPlaying = playback.playingId == messageId
    val isLoading = playback.loadingId == messageId
    val total = if (isPlaying && playback.durationMillis > 0) playback.durationMillis else media.durationMillis ?: 0L
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(min = 200.dp, max = 260.dp),
    ) {
        IconButton(onClick = { playback.toggle(messageId, media) }) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = contentColor)
                isPlaying -> Icon(Icons.Rounded.Pause, contentDescription = "Pause voice message", tint = contentColor)
                else -> Icon(Icons.Rounded.PlayArrow, contentDescription = "Play voice message", tint = contentColor)
            }
        }
        LinearProgressIndicator(
            progress = { if (isPlaying && total > 0) (playback.positionMillis.toFloat() / total).coerceIn(0f, 1f) else 0f },
            color = contentColor,
            trackColor = contentColor.copy(alpha = 0.25f),
            strokeCap = StrokeCap.Round,
            modifier = Modifier.weight(1f).height(4.dp),
        )
        Spacer(Modifier.width(HowYouDoinSpacing.sm))
        Text(
            formatClock(if (isPlaying) playback.positionMillis else total),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

/** Replaces the text field while recording: a discard button, a live level dot and the time. */
@Composable
fun RecordingBar(recording: VoiceRecordingUi, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().height(52.dp)) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Delete, contentDescription = "Discard voice message", tint = MaterialTheme.colorScheme.error)
        }
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            val dot = 10.dp + (14 * recording.level / 255).dp
            Box(Modifier.size(dot).background(MaterialTheme.colorScheme.error, CircleShape))
        }
        Spacer(Modifier.width(HowYouDoinSpacing.sm))
        Text(formatClock(recording.elapsedMillis), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(HowYouDoinSpacing.sm))
        Text(
            "Recording…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = HowYouDoinSpacing.sm),
        )
    }
}

fun formatClock(millis: Long): String {
    val seconds = millis / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** The composer's recording controls and the bubbles' playback, see [rememberVoiceMessages]. */
class VoiceMessagesUi(
    val canRecord: Boolean,
    val recording: VoiceRecordingUi?,
    val start: () -> Unit,
    val cancel: () -> Unit,
    val send: () -> Unit,
    val playback: VoicePlayback?,
)

/**
 * Records voice messages in [format] (null: this chat can't take them) and plays audio messages in the app, one at
 * a time. Recordings arrive in [onRecorded] as attachments marked as voice messages.
 */
@Composable
fun rememberVoiceMessages(
    format: VoiceFormat?,
    loadMedia: suspend (url: String) -> ByteArray?,
    onRecorded: (Attachment) -> Unit,
    onError: (String) -> Unit,
): VoiceMessagesUi {
    val recorder = rememberVoiceRecorder()
    val player = rememberVoicePlayer()
    val scope = rememberCoroutineScope()
    val currentOnError by rememberUpdatedState(onError)
    val currentOnRecorded by rememberUpdatedState(onRecorded)
    val currentLoadMedia by rememberUpdatedState(loadMedia)

    // Recording is on/off by the user only; the timer loop just updates time and level
    var isRecording by remember { mutableStateOf(false) }
    var recordedMillis by remember { mutableStateOf(0L) }
    var level by remember { mutableStateOf(0) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var loadingId by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    LaunchedEffect(isRecording) {
        if (!isRecording || recorder == null) return@LaunchedEffect
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            delay(100)
            recordedMillis = started.elapsedNow().inWholeMilliseconds
            level = recorder.level()
        }
    }
    LaunchedEffect(playingId) {
        if (playingId == null || player == null) return@LaunchedEffect
        while (true) {
            position = player.positionMillis()
            duration = player.durationMillis()
            delay(200)
        }
    }

    val askForMicrophone = rememberMicrophonePermission { granted ->
        when {
            !granted -> currentOnError("Allow the microphone to record voice messages")
            recorder == null || format == null -> Unit
            else -> {
                player?.stop()
                playingId = null
                if (recorder.start(format)) {
                    recordedMillis = 0
                    level = 0
                    isRecording = true
                } else {
                    currentOnError("Couldn't use the microphone")
                }
            }
        }
    }

    val playback = player?.let {
        VoicePlayback(
            playingId = playingId,
            loadingId = loadingId,
            positionMillis = position,
            durationMillis = duration,
            toggle = toggle@{ messageId, media ->
                if (playingId == messageId) {
                    player.stop()
                    playingId = null
                    return@toggle
                }
                player.stop()
                playingId = null
                loadingId = messageId
                scope.launch {
                    val bytes = currentLoadMedia(media.url)
                    loadingId = null
                    when {
                        bytes == null -> currentOnError("Couldn't load the voice message")
                        player.play(bytes, media.mimeType) { if (playingId == messageId) playingId = null } -> {
                            position = 0
                            playingId = messageId
                        }
                        else -> currentOnError("Couldn't play this voice message")
                    }
                }
            },
        )
    }

    return VoiceMessagesUi(
        canRecord = recorder != null && format != null,
        recording = if (isRecording) VoiceRecordingUi(recordedMillis, level) else null,
        start = askForMicrophone,
        cancel = {
            recorder?.cancel()
            isRecording = false
        },
        send = {
            val result = recorder?.stop()
            isRecording = false
            if (result == null) {
                currentOnError("That was too short to send")
            } else {
                currentOnRecorded(
                    Attachment(
                        bytes = result.bytes,
                        fileName = "Voice message.${result.format.extension}",
                        mimeType = result.format.mimeType,
                        voiceDurationMillis = result.durationMillis,
                        waveform = result.waveform,
                    )
                )
            }
        },
        playback = playback,
    )
}
