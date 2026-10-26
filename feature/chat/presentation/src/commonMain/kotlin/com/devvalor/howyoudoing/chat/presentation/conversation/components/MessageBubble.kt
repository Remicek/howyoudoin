package com.devvalor.howyoudoing.chat.presentation.conversation.components

import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.bubble_edited
import howyoudoin.feature.chat.presentation.generated.resources.bubble_sending
import howyoudoin.feature.chat.presentation.generated.resources.bubble_failed
import howyoudoin.feature.chat.presentation.generated.resources.bubble_read
import howyoudoin.feature.chat.presentation.generated.resources.bubble_sent
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devvalor.howyoudoing.chat.domain.MessageStatus
import com.devvalor.howyoudoing.chat.domain.MessageType
import com.devvalor.howyoudoing.chat.domain.Reaction
import com.devvalor.howyoudoing.chat.domain.ReplyPreview
import com.devvalor.howyoudoing.chat.presentation.model.MessageUi
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: MessageUi,
    resolver: MediaResolver,
    onLongPress: () -> Unit,
    onMediaClick: () -> Unit,
    onReactionClick: (String) -> Unit,
) {
    val isOutgoing = message.isOutgoing
    val bubbleColor = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val textColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isOutgoing) 18.dp else 4.dp,
        bottomEnd = if (isOutgoing) 4.dp else 18.dp,
    )
    val media = message.media
    // A media message's body is its file name unless the sender added a caption
    val caption = when {
        media == null -> message.body.asString()
        message.text.isNotBlank() && message.text != media.fileName -> message.text
        else -> null
    }
    // A sticker without a caption shows without a bubble, its time underneath
    val isBareSticker = message.type == MessageType.STICKER && media != null && caption == null && message.replyTo == null
    val metaColor = if (isBareSticker) MaterialTheme.colorScheme.onSurfaceVariant else textColor

    Column(
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        message.senderName?.let { name ->
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = HowYouDoinSpacing.sm, top = HowYouDoinSpacing.xs, bottom = 2.dp),
            )
        }
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(if (isBareSticker) Color.Transparent else bubbleColor)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(
                    horizontal = if (isBareSticker) 0.dp else if (media != null) 6.dp else 14.dp,
                    vertical = if (isBareSticker) 0.dp else if (media != null) 6.dp else HowYouDoinSpacing.sm,
                ),
        ) {
            message.replyTo?.let { ReplyQuote(it, textColor) }
            if (media != null) {
                MessageMedia(message.type, media, resolver, onMediaClick, textColor, messageId = message.id)
            }
            caption?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                    fontStyle = if (message.type in setOf(MessageType.ENCRYPTED, MessageType.EMOTE, MessageType.UNSUPPORTED)) {
                        FontStyle.Italic
                    } else FontStyle.Normal,
                    modifier = Modifier.padding(horizontal = if (media != null) 8.dp else 0.dp, vertical = if (media != null) 4.dp else 0.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.End).padding(horizontal = if (media != null) 6.dp else 0.dp),
            ) {
                Text(
                    if (message.isEdited) stringResource(Res.string.bubble_edited, message.time) else message.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor.copy(alpha = 0.7f)
                )
                if (isOutgoing) {
                    when {
                        message.status == MessageStatus.SENDING -> StatusIcon(Icons.Rounded.Schedule, stringResource(Res.string.bubble_sending), metaColor.copy(alpha = 0.7f))
                        message.status == MessageStatus.FAILED -> StatusIcon(Icons.Rounded.ErrorOutline, stringResource(Res.string.bubble_failed), MaterialTheme.colorScheme.error)
                        message.isReadByOthers -> StatusIcon(Icons.Rounded.DoneAll, stringResource(Res.string.bubble_read), metaColor)
                        else -> StatusIcon(Icons.Rounded.Done, stringResource(Res.string.bubble_sent), metaColor.copy(alpha = 0.7f))
                    }
                }
            }
        }
        if (message.reactions.isNotEmpty()) {
            ReactionRow(message.reactions, onReactionClick)
        }
    }
}

@Composable
private fun ReplyQuote(reply: ReplyPreview, textColor: Color) {
    Row(
        modifier = Modifier
            .padding(bottom = 6.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(textColor.copy(alpha = 0.1f)),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(textColor.copy(alpha = 0.6f)))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (reply.senderName.isNotBlank()) {
                Text(reply.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = textColor)
            }
            Text(reply.body, style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionRow(reactions: List<Reaction>, onReactionClick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
    ) {
        reactions.forEach { reaction ->
            val own = reaction.isOwn
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .then(if (own) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable { onReactionClick(reaction.key) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(reaction.key, style = MaterialTheme.typography.bodyMedium)
                if (reaction.count > 1) {
                    Spacer(Modifier.width(3.dp))
                    Text(reaction.count.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, description: String, tint: Color) {
    Spacer(Modifier.width(HowYouDoinSpacing.xs))
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(14.dp))
}
