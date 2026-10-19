package com.devvalor.howyoudoing.chat.presentation.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.roundToInt

@Composable
actual fun rememberVoiceRecorder(): VoiceRecorder? {
    val context = LocalContext.current
    val recorder = remember { AndroidVoiceRecorder(context.applicationContext) }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }
    return recorder
}

@Composable
actual fun rememberVoicePlayer(): VoicePlayer? {
    val context = LocalContext.current
    val player = remember { AndroidVoicePlayer(context.applicationContext) }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}

@Composable
actual fun rememberMicrophonePermission(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> currentOnResult(granted) }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            currentOnResult(true)
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

private class AndroidVoiceRecorder(private val context: Context) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var format = VoiceFormat.M4A_AAC
    private var startedAt = 0L
    private val levels = ArrayList<Int>()
    private var lastLevel = 0

    override fun start(format: VoiceFormat): Boolean {
        cancel()
        // Android records Ogg/Opus from 10 on; older phones send AAC, which the networks still play
        val actual = if (format == VoiceFormat.OGG_OPUS && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) VoiceFormat.M4A_AAC else format
        val output = File(context.cacheDir, "voice-${System.currentTimeMillis()}.${actual.extension}")
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            when (actual) {
                VoiceFormat.OGG_OPUS -> {
                    created.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    created.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    created.setAudioSamplingRate(48_000)
                    created.setAudioEncodingBitRate(32_000)
                }
                VoiceFormat.M4A_AAC -> {
                    created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    created.setAudioSamplingRate(44_100)
                    created.setAudioEncodingBitRate(64_000)
                }
                VoiceFormat.AAC_ADTS -> {
                    created.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                    created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    created.setAudioSamplingRate(44_100)
                    created.setAudioEncodingBitRate(64_000)
                }
            }
            created.setAudioChannels(1)
            created.setOutputFile(output.absolutePath)
            created.prepare()
            created.start()
            recorder = created
            file = output
            this.format = actual
            startedAt = SystemClock.elapsedRealtime()
            levels.clear()
            true
        } catch (e: Exception) {
            created.release()
            output.delete()
            false
        }
    }

    override fun level(): Int {
        val current = recorder ?: return 0
        // getMaxAmplitude is the peak since the last call; the UI polls this regularly, which also builds the waveform
        lastLevel = runCatching { (current.maxAmplitude / 32767f * 255).roundToInt().coerceIn(0, 255) }.getOrDefault(lastLevel)
        levels += lastLevel
        return lastLevel
    }

    override fun stop(): VoiceRecording? {
        val current = recorder ?: return null
        val output = file
        recorder = null
        file = null
        val duration = SystemClock.elapsedRealtime() - startedAt
        val stopped = runCatching { current.stop() }.isSuccess
        current.release()
        if (!stopped || output == null || duration < MIN_DURATION_MILLIS) {
            output?.delete()
            return null
        }
        val bytes = runCatching { output.readBytes() }.getOrNull()
        output.delete()
        return bytes?.let { VoiceRecording(it, format, duration, waveform(levels)) }
    }

    override fun cancel() {
        val current = recorder ?: return
        recorder = null
        runCatching { current.stop() }
        current.release()
        file?.delete()
        file = null
    }

    /** Squeezes the polled levels into [WAVEFORM_SAMPLES] values, scaled so the loudest is 255. */
    private fun waveform(levels: List<Int>): List<Int> {
        if (levels.isEmpty()) return emptyList()
        val buckets = List(WAVEFORM_SAMPLES) { i ->
            val from = i * levels.size / WAVEFORM_SAMPLES
            val to = maxOf(from + 1, (i + 1) * levels.size / WAVEFORM_SAMPLES).coerceAtMost(levels.size)
            levels.subList(from.coerceAtMost(levels.size - 1), to).max()
        }
        val peak = buckets.max().coerceAtLeast(1)
        return buckets.map { it * 255 / peak }
    }

    private companion object {
        const val MIN_DURATION_MILLIS = 700L
        const val WAVEFORM_SAMPLES = 64
    }
}

private class AndroidVoicePlayer(private val context: Context) : VoicePlayer {
    private var player: MediaPlayer? = null
    private var file: File? = null

    override fun play(bytes: ByteArray, mimeType: String?, onDone: () -> Unit): Boolean {
        stop()
        val extension = when {
            mimeType == null -> "audio"
            "ogg" in mimeType -> "ogg"
            "aac" in mimeType -> "aac"
            "mpeg" in mimeType -> "mp3"
            else -> "m4a"
        }
        val output = File(context.cacheDir, "play.$extension")
        return try {
            output.writeBytes(bytes)
            val created = MediaPlayer()
            created.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            created.setDataSource(output.absolutePath)
            created.setOnCompletionListener { stop(); onDone() }
            created.setOnErrorListener { _, _, _ -> stop(); onDone(); true }
            created.prepare()
            created.start()
            player = created
            file = output
            true
        } catch (e: Exception) {
            output.delete()
            false
        }
    }

    override fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        file?.delete()
        file = null
    }

    override fun positionMillis(): Long = runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: 0L

    override fun durationMillis(): Long = runCatching { player?.duration?.toLong() }.getOrNull()?.takeIf { it > 0 } ?: 0L
}
