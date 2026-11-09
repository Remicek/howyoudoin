package com.devvalor.howyoudoing.chat.presentation.platform

import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.attach_unreadable
import howyoudoin.feature.chat.presentation.generated.resources.attach_too_large
import org.jetbrains.compose.resources.stringResource
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.devvalor.howyoudoing.chat.domain.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

actual val isAttachmentPickerSupported: Boolean = true

@Composable
actual fun rememberAttachmentPicker(onPicked: (Attachment) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnPicked by rememberUpdatedState(onPicked)
    val currentOnError by rememberUpdatedState(onError)
    val unreadable = stringResource(Res.string.attach_unreadable)
    val tooLarge = stringResource(Res.string.attach_too_large)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val attachment = withContext(Dispatchers.IO) { runCatching { readAttachment(context, uri) }.getOrNull() }
            when {
                attachment == null -> currentOnError(unreadable)
                attachment.bytes.size > MAX_ATTACHMENT_BYTES -> currentOnError(tooLarge)
                else -> currentOnPicked(attachment)
            }
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

private fun readAttachment(context: Context, uri: Uri): Attachment {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "file"
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Unreadable")
    var width: Int? = null
    var height: Int? = null
    var durationMillis: Long? = null
    if (mimeType.startsWith("image/")) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        width = options.outWidth.takeIf { it > 0 }
        height = options.outHeight.takeIf { it > 0 }
    } else if (mimeType.startsWith("video/")) {
        videoMeta(context, uri)?.let { (w, h, ms) -> width = w; height = h; durationMillis = ms }
    }
    return Attachment(bytes, name, mimeType, width, height, durationMillis = durationMillis)
}

/** A video's size as shown (rotation applied) and length, which networks need to show it in its shape. */
private fun videoMeta(context: Context, uri: Uri): Triple<Int?, Int?, Long?>? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull()
        val w = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()?.takeIf { it > 0 }
        val h = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()?.takeIf { it > 0 }
        val rotation = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
        val rotated = rotation % HALF_TURN == QUARTER_TURN
        val ms = number(MediaMetadataRetriever.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        if (rotated) Triple(h, w, ms) else Triple(w, h, ms)
    } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
        // Not a video Android can read: it's sent without its size
        null
    } finally {
        retriever.release()
    }
}

private const val HALF_TURN = 180L
private const val QUARTER_TURN = 90L

@Composable
actual fun rememberFileOpener(): (bytes: ByteArray, fileName: String, mimeType: String?) -> Unit {
    val context = LocalContext.current
    return { bytes, fileName, mimeType ->
        // Shared through FileProvider (declared in the app manifest, authority "<package>.files")
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // The name comes from the network: no path separators, and not "", "." or ".." (the folder itself)
        val safeName = fileName.replace(Regex("[/\\\\:]"), "_").takeUnless { it.isBlank() || it.all { c -> c == '.' } }
            ?: "file"
        val file = File(dir, safeName).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType ?: context.contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(intent, fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No app can open it; nothing else to do
        }
    }
}
