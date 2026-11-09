package com.devvalor.howyoudoing.chat.presentation.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.UIKit.UIApplication

/** Presented with the system's player, inside the app. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VideoPlayerDialog(video: PlayableVideo, onClose: () -> Unit) {
    LaunchedEffect(video) {
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + "playing-video.mp4")
        val data = video.bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = video.bytes.size.toULong()) }
        data.writeToURL(url, atomically = true)
        val player = AVPlayer(uRL = url)
        val controller = AVPlayerViewController().apply { this.player = player }
        UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(
            controller,
            animated = true,
            completion = { player.play() }
        )
        onClose()
    }
}

// No frame extraction on iOS yet: videos show the network's preview picture, if any
@Composable
actual fun rememberVideoFrame(url: String, headers: Map<String, String>): ImageBitmap? = null
