package com.devvalor.howyoudoing.chat.presentation.conversation.components

import howyoudoin.feature.chat.presentation.generated.resources.media_photo
import howyoudoin.feature.chat.presentation.generated.resources.media_video
import howyoudoin.feature.chat.presentation.generated.resources.media_audio
import howyoudoin.feature.chat.presentation.generated.resources.media_file
import howyoudoin.feature.chat.presentation.generated.resources.media_tap_to_open
import howyoudoin.feature.chat.presentation.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import howyoudoin.feature.chat.presentation.generated.resources.messagemedia_sticker
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.devvalor.howyoudoing.chat.domain.MediaInfo
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing

/** Resolves an `mxc://` URI to an authenticated HTTP request (see ChatRepository.resolveMedia). */
typealias MediaResolver = suspend (mxcUrl: String, thumbnail: Boolean) -> MediaRequest?

/** Loads media through Coil with the auth headers the media repository requires. */
@Composable
fun AuthenticatedImage(
    mxcUrl: String,
    resolver: MediaResolver,
    thumbnail: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** Shown behind the image while it loads; stickers use none (they have transparent backgrounds). */
    placeholderColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    var request by remember(mxcUrl, thumbnail) { mutableStateOf<MediaRequest?>(null) }
    var failed by remember(mxcUrl) { mutableStateOf(false) }
    // A failed load is tried again a couple of times (a file still being saved, a network blip) before giving up
    var attempt by remember(mxcUrl) { mutableIntStateOf(0) }
    var retryPending by remember(mxcUrl) { mutableStateOf(false) }
    LaunchedEffect(mxcUrl, thumbnail) { request = resolver(mxcUrl, thumbnail) }
    LaunchedEffect(retryPending) {
        if (!retryPending) return@LaunchedEffect
        delay(IMAGE_RETRY_DELAY_MILLIS * (attempt + 1))
        attempt++
        retryPending = false
    }
    val context = LocalPlatformContext.current
    Box(modifier = modifier.background(placeholderColor), contentAlignment = Alignment.Center) {
        val resolved = request
        if (resolved != null && !failed) {
            AsyncImage(
                model = remember(resolved, attempt) {
                    ImageRequest.Builder(context)
                        .data(resolved.url)
                        .httpHeaders(NetworkHeaders.Builder().apply { resolved.headers.forEach { (k, v) -> set(k, v) } }.build())
                        .build()
                },
                contentDescription = contentDescription,
                contentScale = contentScale,
                onError = { if (attempt < IMAGE_RETRIES) retryPending = true else failed = true },
                modifier = Modifier.matchParentSize(),
            )
        } else if (failed) {
            Icon(Icons.Rounded.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A chat's picture for [ChatAvatar]'s image slot; draws nothing until (and unless) it loads. */
@Composable
fun AvatarImage(url: String, resolver: MediaResolver, modifier: Modifier = Modifier) {
    var request by remember(url) { mutableStateOf<MediaRequest?>(null) }
    LaunchedEffect(url) { request = resolver(url, true) }
    val context = LocalPlatformContext.current
    val resolved = request ?: return
    AsyncImage(
        model = remember(resolved) {
            ImageRequest.Builder(context)
                .data(resolved.url)
                .httpHeaders(NetworkHeaders.Builder().apply { resolved.headers.forEach { (k, v) -> set(k, v) } }.build())
                .build()
        },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}

/** Photo thumbnail sized by its aspect ratio, or a file card for video / audio / files. */
@Composable
fun MessageMedia(
    type: MessageType,
    media: MediaInfo,
    resolver: MediaResolver,
    onClick: () -> Unit,
    contentColor: Color,
    /** Lets audio play inside the bubble (see [LocalVoicePlayback]). */
    messageId: String? = null,
) {
    when (type) {
        // Stickers stand on their own, without a frame, like in the networks' apps
        MessageType.STICKER -> AuthenticatedImage(
            mxcUrl = media.url,
            resolver = resolver,
            thumbnail = true,
            contentDescription = stringResource(Res.string.messagemedia_sticker),
            contentScale = ContentScale.Fit,
            placeholderColor = Color.Transparent,
            modifier = Modifier.size(STICKER_SIZE).clickable(onClick = onClick),
        )
        MessageType.IMAGE -> {
            val width = media.width
            val height = media.height
            val ratio = if (width != null && height != null && height > 0) {
                (width.toFloat() / height).coerceIn(0.5f, 2f)
            } else 4f / 3f
            AuthenticatedImage(
                mxcUrl = media.url,
                resolver = resolver,
                thumbnail = true,
                contentDescription = media.fileName ?: stringResource(Res.string.media_photo),
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onClick),
            )
        }
        MessageType.AUDIO -> {
            val playback = LocalVoicePlayback.current
            if (playback != null && messageId != null) VoiceNote(messageId, media, playback, contentColor)
            else FileCard(type, media, onClick, contentColor)
        }
        else -> FileCard(type, media, onClick, contentColor)
    }
}

@Composable
private fun FileCard(type: MessageType, media: MediaInfo, onClick: () -> Unit, contentColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(min = 180.dp, max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(contentColor.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(HowYouDoinSpacing.sm),
    ) {
        Icon(
            when (type) {
                MessageType.VIDEO -> Icons.Rounded.PlayCircle
                MessageType.AUDIO -> Icons.Rounded.Audiotrack
                else -> Icons.AutoMirrored.Rounded.InsertDriveFile
            },
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.width(HowYouDoinSpacing.sm))
        Column {
            Text(
                media.fileName ?: when (type) {
                    MessageType.VIDEO -> stringResource(Res.string.media_video)
                    MessageType.AUDIO -> stringResource(Res.string.media_audio)
                    else -> stringResource(Res.string.media_file)
                },
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(media.sizeBytes?.let(::formatSize), media.durationMillis?.let(::formatDuration), stringResource(Res.string.media_tap_to_open))
                    .joinToString(" · "),
                color = contentColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${(bytes * 10 / 1_048_576) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private val STICKER_SIZE = 160.dp

private const val IMAGE_RETRIES = 2
private const val IMAGE_RETRY_DELAY_MILLIS = 1_500L
