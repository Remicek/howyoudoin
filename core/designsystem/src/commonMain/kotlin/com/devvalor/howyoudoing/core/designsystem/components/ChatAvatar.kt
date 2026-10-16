package com.devvalor.howyoudoing.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlin.math.absoluteValue

private val avatarPalette = listOf(
    Color(0xFFEF8354), Color(0xFF6C8EBF), Color(0xFF8E7DBE), Color(0xFF4DA167),
    Color(0xFFD1495B), Color(0xFF00A6A6), Color(0xFFE0A458), Color(0xFF7D5BA6),
)

/**
 * Initials avatar with an optional network badge in the bottom-right corner.
 * TODO: load mxc:// avatars once authenticated media loading is in place.
 */
@Composable
fun ChatAvatar(
    name: String,
    modifier: Modifier = Modifier,
    network: ChatNetwork? = null,
    size: Dp = 52.dp,
) {
    Box(modifier = modifier.size(size)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .background(avatarColor(name), CircleShape),
        ) {
            Text(
                text = initials(name),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.36f).sp,
            )
        }
        if (network != null) {
            NetworkBadge(
                network = network,
                size = size * 0.4f,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp),
            )
        }
    }
}

private fun avatarColor(name: String): Color =
    avatarPalette[name.hashCode().absoluteValue % avatarPalette.size]

private fun initials(name: String): String {
    // Skip emoji and symbols, taking one char of those would split a surrogate pair.
    val parts = name.trim().split(" ").filter { it.firstOrNull()?.isLetterOrDigit() == true }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts.first().take(1).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}
