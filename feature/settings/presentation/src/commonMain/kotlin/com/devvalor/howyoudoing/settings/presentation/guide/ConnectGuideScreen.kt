package com.devvalor.howyoudoing.settings.presentation.guide

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.settings.presentation.components.SettingsScaffold

/** One network's way of connecting, in the user's words. */
private data class NetworkGuide(val name: String, val steps: String)

private val guides = listOf(
    NetworkGuide(
        "WhatsApp",
        "Choose \"pairing code\" if WhatsApp is on this phone: WhatsApp → Linked devices → Link a device → " +
            "Link with phone number instead, and type the code HowYouDoin shows. With WhatsApp on another phone, " +
            "\"QR code\" works too.",
    ),
    NetworkGuide(
        "Messenger and Instagram",
        "Log in on the Facebook or Instagram page that opens inside HowYouDoin. For encrypted Messenger chats, " +
            "enter your Messenger PIN once to bring back their history.",
    ),
    NetworkGuide(
        "Telegram",
        "Choose \"phone number\": Telegram sends a login code to the Telegram app (or by SMS). The QR code option " +
            "needs a second screen, because Telegram only scans codes with the camera.",
    ),
    NetworkGuide(
        "Signal",
        "HowYouDoin joins your Signal account as a linked device, like Signal Desktop: in Signal open Settings → " +
            "Linked devices → Link a new device and scan the code. Signal scans with the camera, so the code has " +
            "to be on a different screen from the phone Signal runs on.",
    ),
)

@Composable
fun ConnectGuideRoot(onNetworksClick: () -> Unit, onBack: () -> Unit) {
    SettingsScaffold(title = "How connecting works", onBack = onBack) {
        item {
            Paragraph(
                "Your chats connect straight from this phone; no server stores them. HowYouDoin keeps the " +
                    "connections open in the background, which is what the \"Connected on this device\" notification means.",
            )
        }
        guides.forEach { guide ->
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm)) {
                    Text(
                        guide.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(HowYouDoinSpacing.xs))
                    Text(guide.steps, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Paragraph(
                "HowYouDoin is unofficial: these networks don't make or support it. WhatsApp, Messenger, Instagram " +
                    "and Signal don't officially allow other apps, so they could limit an account that uses one.",
            )
        }
        item {
            FilledTonalButton(
                onClick = onNetworksClick,
                modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.md),
            ) { Text("Connect a network") }
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
    )
}
