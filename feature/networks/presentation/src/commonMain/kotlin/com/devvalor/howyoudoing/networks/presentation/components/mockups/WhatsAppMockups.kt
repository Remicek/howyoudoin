package com.devvalor.howyoudoing.networks.presentation.components.mockups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val textDark = Color(0xFF111B21)
private val textMuted = Color(0xFF667781)

@Composable
private fun Header(title: String) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier.fillMaxWidth().background(MockColors.header).padding(start = 14.dp, top = 22.dp, bottom = 10.dp),
    ) {
        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
        Icon(icon, contentDescription = null, tint = textMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = textDark, fontSize = 11.sp)
    }
}

/** Settings with "Linked devices" highlighted. */
@Composable
fun MockSettingsScreen() = PhoneMockup {
    Header("Settings")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
        Box(Modifier.size(34.dp).background(MockColors.placeholder, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Placeholder(70.dp)
            Placeholder(50.dp, 6.dp)
        }
    }
    SettingsRow(Icons.Rounded.Key, "Account")
    SettingsRow(Icons.Rounded.Lock, "Privacy")
    Highlighted(Modifier.padding(horizontal = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(MockColors.accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 9.dp),
        ) {
            Icon(Icons.Rounded.Devices, contentDescription = null, tint = MockColors.header, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
            Text("Linked devices", color = textDark, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    SettingsRow(Icons.AutoMirrored.Rounded.Chat, "Chats")
    SettingsRow(Icons.Rounded.Notifications, "Notifications")
}

/** Linked devices with the "Link a device" button highlighted. */
@Composable
fun MockLinkedDevicesScreen() = PhoneMockup {
    Header("Linked devices")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(14.dp)) {
        Spacer(Modifier.height(10.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(70.dp).background(MockColors.accent.copy(alpha = 0.15f), CircleShape)) {
            Icon(Icons.Rounded.Laptop, contentDescription = null, tint = MockColors.header, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("Use WhatsApp on other devices", color = textDark, fontSize = 11.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Highlighted {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(140.dp).background(MockColors.header, RoundedCornerShape(20.dp)).padding(vertical = 8.dp),
            ) {
                Text("Link a device", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(26.dp))
        Placeholder(120.dp)
        Spacer(Modifier.height(6.dp))
        Placeholder(90.dp, 6.dp)
    }
}

/** Camera screen with "Link with phone number instead" highlighted. */
@Composable
fun MockScannerScreen() = PhoneMockup {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().background(MockColors.scanner).padding(14.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text("Scan QR code", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.size(130.dp).border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp)))
        Spacer(Modifier.weight(1f))
        Highlighted(tapMarkerColor = Color.White) {
            Text(
                "Link with phone number instead",
                color = MockColors.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
    }
}

/** Code entry boxes, filled with an example code. */
@Composable
fun MockCodeEntryScreen() = PhoneMockup {
    Header("Link with phone number")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Spacer(Modifier.height(14.dp))
        Text("Enter code on phone", color = textDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Highlighted {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(6.dp)) {
                listOf("1CDB", "N2DG").forEach { group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        group.forEach { char ->
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(26.dp, 30.dp).border(1.dp, textMuted, RoundedCornerShape(5.dp)),
                            ) {
                                Text(char.toString(), color = textDark, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Placeholder(130.dp, 6.dp)
        Spacer(Modifier.height(6.dp))
        Placeholder(100.dp, 6.dp)
    }
}

/** HowYouDoin on a tablet/computer showing a QR, scanned by the phone. */
@Composable
fun MockQrOtherScreen() {
    Row(verticalAlignment = Alignment.Bottom) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(170.dp, 120.dp)
                .border(5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                .background(Color.White, RoundedCornerShape(12.dp)),
        ) {
            Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = Color.Black, modifier = Modifier.size(78.dp))
        }
        Spacer(Modifier.width(14.dp))
        PhoneMockup(width = 80.dp) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().background(MockColors.scanner)) {
                Box(Modifier.size(44.dp).border(1.5.dp, Color.White, RoundedCornerShape(6.dp)))
            }
        }
    }
}

/** HowYouDoin ⇄ WhatsApp on your phone. */
@Composable
fun MockLinkIntro() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(84.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
        }
        Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp).size(32.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(84.dp).background(MockColors.accent.copy(alpha = 0.18f), RoundedCornerShape(24.dp)),
        ) {
            Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = MockColors.header, modifier = Modifier.size(42.dp))
        }
    }
}
