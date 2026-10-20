package com.devvalor.howyoudoing.chat.presentation.conversation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devvalor.howyoudoing.chat.presentation.model.MessageUi
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing

@Composable
fun Composer(
    draft: String,
    canSend: Boolean,
    replyingTo: MessageUi?,
    isSendingAttachment: Boolean,
    canAttach: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onCancelReply: () -> Unit,
    /** Voice messages can be recorded here (empty draft shows a microphone instead of send). */
    canRecord: Boolean = false,
    recording: VoiceRecordingUi? = null,
    onRecordStart: () -> Unit = {},
    onRecordCancel: () -> Unit = {},
    onRecordSend: () -> Unit = {},
    /** One of our messages being edited: shown above the field, and send saves the edit. */
    editing: MessageUi? = null,
    onCancelEdit: () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (editing != null) {
                ReplyBar(editing, onCancelEdit, label = "Editing message", cancelDescription = "Cancel editing")
            } else {
                replyingTo?.let { ReplyBar(it, onCancelReply) }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth().padding(horizontal = HowYouDoinSpacing.sm, vertical = HowYouDoinSpacing.sm),
            ) {
                if (recording != null) {
                    RecordingBar(recording, onCancel = onRecordCancel, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(HowYouDoinSpacing.sm))
                    FilledIconButton(onClick = onRecordSend, shape = CircleShape, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send voice message")
                    }
                    return@Row
                }
                if (canAttach) {
                    IconButton(onClick = onAttach, enabled = !isSendingAttachment, modifier = Modifier.padding(bottom = 4.dp)) {
                        if (isSendingAttachment) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.AttachFile, contentDescription = "Attach a photo or file")
                        }
                    }
                }
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text("Message") },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(HowYouDoinSpacing.sm))
                if (canRecord && draft.isBlank() && editing == null) {
                    FilledIconButton(
                        onClick = onRecordStart,
                        enabled = !isSendingAttachment,
                        shape = CircleShape,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Record a voice message")
                    }
                } else {
                    FilledIconButton(onClick = onSend, enabled = canSend, shape = CircleShape, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyBar(
    message: MessageUi,
    onCancel: () -> Unit,
    label: String? = null,
    cancelDescription: String = "Cancel reply",
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HowYouDoinSpacing.md, end = HowYouDoinSpacing.xs, top = HowYouDoinSpacing.sm)
            .height(48.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(HowYouDoinSpacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                label ?: if (message.isOutgoing) "Replying to yourself" else "Replying to ${message.senderName ?: "message"}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(message.body, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = cancelDescription) }
    }
}
