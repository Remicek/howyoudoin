package com.devvalor.howyoudoing.chat.presentation.platform

import androidx.compose.runtime.Composable

// iOS builds are on hold; recording and playback aren't implemented there yet

@Composable
actual fun rememberVoiceRecorder(): VoiceRecorder? = null

@Composable
actual fun rememberVoicePlayer(): VoicePlayer? = null

@Composable
actual fun rememberMicrophonePermission(onResult: (granted: Boolean) -> Unit): () -> Unit = { onResult(false) }
