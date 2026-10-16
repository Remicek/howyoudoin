package com.devvalor.howyoudoing.chat.presentation.platform

import androidx.compose.runtime.Composable
import com.devvalor.howyoudoing.chat.domain.Attachment

/** False where the platform picker isn't implemented yet (iOS: TODO PHPicker). */
expect val isAttachmentPickerSupported: Boolean

/** Returns a function that opens the system file picker and reports the chosen file. */
@Composable
expect fun rememberAttachmentPicker(onPicked: (Attachment) -> Unit, onError: (String) -> Unit): () -> Unit

/** Returns a function that hands a downloaded file to another app (viewer, player, share sheet). */
@Composable
expect fun rememberFileOpener(): (bytes: ByteArray, fileName: String, mimeType: String?) -> Unit

internal const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
