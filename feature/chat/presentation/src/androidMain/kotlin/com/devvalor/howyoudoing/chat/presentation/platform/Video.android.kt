package com.devvalor.howyoudoing.chat.presentation.platform

import android.os.Build
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun VideoPlayerDialog(video: PlayableVideo, onClose: () -> Unit) {
    val context = LocalContext.current
    var file by remember(video) { mutableStateOf<File?>(null) }
    LaunchedEffect(video) {
        file = withContext(Dispatchers.IO) {
            val extension = video.fileName?.substringAfterLast('.', "")?.takeIf { it.length in 2..4 }
                ?: video.mimeType?.substringAfter('/')?.takeIf { it.length in 2..4 } ?: "mp4"
            File(context.cacheDir, "playing-video.$extension").apply { writeBytes(video.bytes) }
        }
    }
    DisposableEffect(video) { onDispose { file?.delete() } }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            file?.let { path ->
                AndroidView(
                    factory = { ctx ->
                        // Given the whole screen, the player fits the video in it keeping its proportions; the frame
                        // centres it
                        FrameLayout(ctx).apply {
                            val player = VideoView(ctx)
                            addView(
                                player,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    Gravity.CENTER
                                )
                            )
                            val controls = MediaController(ctx)
                            controls.setAnchorView(player)
                            player.setMediaController(controls)
                            player.setVideoPath(path.absolutePath)
                            player.setOnPreparedListener { player.start(); controls.show() }
                            player.setOnCompletionListener { controls.show(0) }
                        }
                    },
                    onRelease = { frame -> (frame.getChildAt(0) as? VideoView)?.stopPlayback() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
            }
        }
    }
}

// MediaMetadataRetriever reports unreadable files with IllegalArgumentException or RuntimeException
@Suppress("TooGenericExceptionCaught", "SwallowedException")
@Composable
actual fun rememberVideoFrame(url: String, headers: Map<String, String>): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, url, headers) {
        val isFile = url.startsWith("file://")
        if (!isFile && !url.startsWith("https://")) return@produceState
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                if (isFile) {
                    retriever.setDataSource(url.removePrefix("file://"))
                } else {
                    retriever.setDataSource(url, headers)
                }
                retriever.frameAt(FRAME_AT_MICROS)?.asImageBitmap()
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
    }
    return state.value
}

/** The frame at [micros], at most [FRAME_SIZE] on its longer side (Android 8.0 can only take it full size). */
private fun MediaMetadataRetriever.frameAt(micros: Long): Bitmap? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_SIZE, FRAME_SIZE)
    } else {
        getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
            val scale = FRAME_SIZE.toFloat() / maxOf(frame.width, frame.height)
            if (scale >= 1f) frame else {
                Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
            }
        }
    }

/** A frame a moment in, past a black first frame. */
private const val FRAME_AT_MICROS = 500_000L

/** Big enough for the chat's preview. */
private const val FRAME_SIZE = 720
