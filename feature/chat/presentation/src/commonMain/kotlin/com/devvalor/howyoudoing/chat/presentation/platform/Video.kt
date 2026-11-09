package com.devvalor.howyoudoing.chat.presentation.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** A downloaded video to play in the app. */
class PlayableVideo(val bytes: ByteArray, val mimeType: String?, val fileName: String?)

/** Plays [video] full screen inside the app; [onClose] when the user leaves it. */
@Composable
expect fun VideoPlayerDialog(video: PlayableVideo, onClose: () -> Unit)

/**
 * A frame from a video saved on the device (`file://` URL) or on the web (`https://`, read without downloading all of
 * it, with [headers] such as a login), for its preview in the chat; null until (or unless) it is read.
 */
@Composable
expect fun rememberVideoFrame(url: String, headers: Map<String, String> = emptyMap()): ImageBitmap?
