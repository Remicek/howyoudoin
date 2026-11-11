package com.devvalor.howyoudoing.chat.presentation.conversation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.devvalor.howyoudoing.chat.presentation.conversation.LinkCallPrompt
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.link_call_cancel
import howyoudoin.feature.chat.presentation.generated.resources.link_call_dont_show
import howyoudoin.feature.chat.presentation.generated.resources.link_call_how_browser
import howyoudoin.feature.chat.presentation.generated.resources.link_call_how_encrypted
import howyoudoin.feature.chat.presentation.generated.resources.link_call_how_group
import howyoudoin.feature.chat.presentation.generated.resources.link_call_how_link
import howyoudoin.feature.chat.presentation.generated.resources.link_call_how_new
import howyoudoin.feature.chat.presentation.generated.resources.link_call_how_ringing
import howyoudoin.feature.chat.presentation.generated.resources.link_call_start
import howyoudoin.feature.chat.presentation.generated.resources.link_call_title
import howyoudoin.feature.chat.presentation.generated.resources.link_call_title_video
import org.jetbrains.compose.resources.stringResource

/**
 * What happens with a call through a link, before it starts: it's a new call (not the network's), its link is sent in
 * this chat, the other person joins in a browser, and (calling back) their call on the network keeps ringing.
 *
 * @param networkName e.g. "WhatsApp"
 * @param chatName who the link goes to
 */
/** The most people in a group call (GROUP_SIZE in call-link/src/room.ts). */
private const val GROUP_CALL_SIZE = 6

@Composable
fun LinkCallDialog(
    prompt: LinkCallPrompt,
    networkName: String,
    chatName: String,
    onConfirm: (dontShowAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (prompt.video) Icons.Rounded.Videocam else Icons.Rounded.Call, contentDescription = null) },
        title = {
            Text(stringResource(if (prompt.video) Res.string.link_call_title_video else Res.string.link_call_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.md),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Point(Icons.Rounded.Call, stringResource(Res.string.link_call_how_new, networkName))
                Point(Icons.Rounded.Send, stringResource(Res.string.link_call_how_link, chatName))
                Point(Icons.Rounded.Public, stringResource(Res.string.link_call_how_browser))
                if (prompt.isGroup) {
                    Point(Icons.Rounded.Groups, stringResource(Res.string.link_call_how_group, GROUP_CALL_SIZE))
                }
                if (prompt.isCallBack) {
                    Point(
                        Icons.Rounded.NotificationsActive,
                        stringResource(Res.string.link_call_how_ringing, networkName)
                    )
                }
                Point(Icons.Rounded.Lock, stringResource(Res.string.link_call_how_encrypted))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(
                        value = dontShowAgain,
                        role = Role.Checkbox,
                        onValueChange = { dontShowAgain = it },
                    ),
                ) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = null)
                    Spacer(Modifier.width(HowYouDoinSpacing.sm))
                    Text(stringResource(Res.string.link_call_dont_show), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontShowAgain) }) { Text(stringResource(Res.string.link_call_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.link_call_cancel)) }
        },
    )
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Spacer(Modifier.width(HowYouDoinSpacing.md))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
