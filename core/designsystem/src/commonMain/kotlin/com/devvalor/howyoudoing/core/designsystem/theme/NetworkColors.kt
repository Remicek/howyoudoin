package com.devvalor.howyoudoing.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

val ChatNetwork.brandColor: Color
    get() = when (this) {
        ChatNetwork.WHATSAPP -> Color(0xFF25D366)
        ChatNetwork.MESSENGER -> Color(0xFF0084FF)
        ChatNetwork.TELEGRAM -> Color(0xFF2AABEE)
        ChatNetwork.SIGNAL -> Color(0xFF3A76F0)
        ChatNetwork.INSTAGRAM -> Color(0xFFE1306C)
        ChatNetwork.SLACK -> Color(0xFF611F69)
        ChatNetwork.GOOGLE_MESSAGES -> Color(0xFF1A73E8)
        ChatNetwork.MATRIX -> Color(0xFF0DBD8B)
    }

/** Short mark shown inside the network badge, we deliberately don't ship brand logos. */
val ChatNetwork.monogram: String
    get() = when (this) {
        ChatNetwork.WHATSAPP -> "W"
        ChatNetwork.MESSENGER -> "M"
        ChatNetwork.TELEGRAM -> "T"
        ChatNetwork.SIGNAL -> "S"
        ChatNetwork.INSTAGRAM -> "I"
        ChatNetwork.SLACK -> "S"
        ChatNetwork.GOOGLE_MESSAGES -> "G"
        ChatNetwork.MATRIX -> "[m]"
    }
