package com.devvalor.howyoudoing.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

/** The networks' brand colours (as Simple Icons lists them, see NetworkIcons.kt). */
val ChatNetwork.brandColor: Color
    get() = when (this) {
        ChatNetwork.WHATSAPP -> Color(0xFF25D366)
        ChatNetwork.MESSENGER -> Color(0xFF0866FF)
        ChatNetwork.TELEGRAM -> Color(0xFF26A5E4)
        ChatNetwork.SIGNAL -> Color(0xFF3B45FD)
        ChatNetwork.INSTAGRAM -> Color(0xFFFF0069)
        ChatNetwork.SLACK -> Color(0xFF611F69)
        ChatNetwork.GOOGLE_MESSAGES -> Color(0xFF1A73E8)
        ChatNetwork.BLUESKY -> Color(0xFF1185FE)
        // Not in Simple Icons: LinkedIn's published blue, and Google's green for Voice
        ChatNetwork.LINKEDIN -> Color(0xFF0A66C2)
        ChatNetwork.DISCORD -> Color(0xFF5865F2)
        ChatNetwork.GOOGLE_VOICE -> Color(0xFF1E8E3E)
        ChatNetwork.MATRIX -> Color(0xFF000000)
    }

/** Letter shown in the network badge where there's no [logo] (LinkedIn, Google Voice). */
val ChatNetwork.monogram: String
    get() = when (this) {
        ChatNetwork.WHATSAPP -> "W"
        ChatNetwork.MESSENGER -> "M"
        ChatNetwork.TELEGRAM -> "T"
        ChatNetwork.SIGNAL -> "S"
        ChatNetwork.INSTAGRAM -> "I"
        ChatNetwork.SLACK -> "S"
        ChatNetwork.GOOGLE_MESSAGES -> "M"
        ChatNetwork.BLUESKY -> "B"
        ChatNetwork.LINKEDIN -> "in"
        ChatNetwork.DISCORD -> "D"
        ChatNetwork.GOOGLE_VOICE -> "V"
        ChatNetwork.MATRIX -> "[m]"
    }
