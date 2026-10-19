package com.devvalor.howyoudoing.chat.presentation.platform

import androidx.compose.runtime.Composable

/**
 * How a voice message is recorded. Each network's apps expect their own format for voice notes: WhatsApp and
 * Telegram Ogg/Opus, Messenger and Instagram AAC in MP4, Signal raw AAC.
 */
enum class VoiceFormat(val mimeType: String, val extension: String) {
    OGG_OPUS("audio/ogg", "ogg"),
    M4A_AAC("audio/mp4", "m4a"),
    AAC_ADTS("audio/aac", "aac")
}

class VoiceRecording(
    val bytes: ByteArray,
    val format: VoiceFormat,
    val durationMillis: Long,
    /** Loudness over the recording, 0–255 per sample. */
    val waveform: List<Int>
)

interface VoiceRecorder {
    /** Starts recording; false if the microphone couldn't be used. Formats a phone can't record fall back to M4A. */
    fun start(format: VoiceFormat): Boolean

    /** Ends the recording; null if it was too short or failed. */
    fun stop(): VoiceRecording?

    fun cancel()

    /** Current loudness, 0–255, for the recording indicator. */
    fun level(): Int
}

interface VoicePlayer {
    /** Plays audio from memory; [onDone] runs when it finishes or fails. False if it can't be played. */
    fun play(bytes: ByteArray, mimeType: String?, onDone: () -> Unit): Boolean

    fun stop()

    fun positionMillis(): Long

    fun durationMillis(): Long
}

/** Null where recording isn't implemented (iOS). */
@Composable
expect fun rememberVoiceRecorder(): VoiceRecorder?

/** Null where playback isn't implemented (iOS). */
@Composable
expect fun rememberVoicePlayer(): VoicePlayer?

/** Returns a function that asks for the microphone if needed and reports whether it may be used. */
@Composable
expect fun rememberMicrophonePermission(onResult: (granted: Boolean) -> Unit): () -> Unit
