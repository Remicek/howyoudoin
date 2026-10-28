package com.devvalor.howyoudoing.chat.presentation.conversation.components

import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.actions_send_again
import howyoudoin.feature.chat.presentation.generated.resources.actions_copy
import howyoudoin.feature.chat.presentation.generated.resources.actions_delete
import howyoudoin.feature.chat.presentation.generated.resources.actions_reply
import howyoudoin.feature.chat.presentation.generated.resources.actions_edit
import howyoudoin.feature.chat.presentation.generated.resources.actions_delete_everyone
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devvalor.howyoudoing.chat.presentation.model.MessageUi
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing

/** Quick reactions, as in WhatsApp and Messenger. */
val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    message: MessageUi,
    onReaction: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    /** Set for our own messages that can be edited / deleted for everyone. */
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** For messages that failed to send. */
    onRetry: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // A message that didn't send: only sending it again or deleting it make sense
        if (message.status == MessageStatus.FAILED) {
            Column(Modifier.navigationBarsPadding().padding(bottom = HowYouDoinSpacing.md)) {
                if (message.canRetry && onRetry != null) ActionRow(
                    Icons.Rounded.Refresh,
                    stringResource(Res.string.actions_send_again),
                    onRetry
                )
                if (message.text.isNotBlank() && message.media == null) ActionRow(
                    Icons.Rounded.ContentCopy,
                    stringResource(Res.string.actions_copy),
                    onCopy
                )
                onDiscard?.let { ActionRow(Icons.Rounded.Delete, stringResource(Res.string.actions_delete), it) }
            }
            return@ModalBottomSheet
        }
        Column(Modifier.navigationBarsPadding().padding(bottom = HowYouDoinSpacing.md)) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
            ) {
                QuickReactions.forEach { emoji ->
                    val isOwn = message.reactions.any { it.key == emoji && it.isOwn }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (isOwn) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                CircleShape
                            )
                            .clickable { onReaction(emoji) },
                    ) {
                        Text(emoji, fontSize = 24.sp)
                    }
                }
            }
            ActionRow(Icons.AutoMirrored.Rounded.Reply, stringResource(Res.string.actions_reply), onReply)
            if (message.text.isNotBlank() && message.media == null) {
                ActionRow(Icons.Rounded.ContentCopy, stringResource(Res.string.actions_copy), onCopy)
            }
            onEdit?.let { ActionRow(Icons.Rounded.Edit, stringResource(Res.string.actions_edit), it) }
            onDelete?.let { ActionRow(Icons.Rounded.Delete, stringResource(Res.string.actions_delete_everyone), it) }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = HowYouDoinSpacing.lg, vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(HowYouDoinSpacing.md))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
