package com.devvalor.howyoudoing.chat.presentation.platform

import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.attach_ios
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.Composable
import com.devvalor.howyoudoing.chat.domain.Attachment
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

// TODO: PHPickerViewController / UIDocumentPickerViewController based picker
actual val isAttachmentPickerSupported: Boolean = false

@Composable
actual fun rememberAttachmentPicker(onPicked: (Attachment) -> Unit, onError: (String) -> Unit): () -> Unit {
    val unavailable = stringResource(Res.string.attach_ios)
    return { onError(unavailable) }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberFileOpener(): (bytes: ByteArray, fileName: String, mimeType: String?) -> Unit = { bytes, fileName, _ ->
    val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + fileName.replace("/", "_"))
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    data.writeToURL(url, atomically = true)
    val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
    UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(sheet, animated = true, completion = null)
}
