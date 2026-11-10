package com.devvalor.howyoudoing.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devvalor.howyoudoing.core.domain.ondevice.AccountLabels

/** The colours an account can have, to tell a network's accounts apart ([AccountLabels.COLOURS] of them). */
val AccountColours: List<Color> = listOf(
    Color(0xFF1E88E5), // blue
    Color(0xFFE53935), // red
    Color(0xFF43A047), // green
    Color(0xFFFB8C00), // orange
    Color(0xFF8E24AA), // purple
    Color(0xFF00897B) // teal
)

fun accountColour(index: Int): Color = AccountColours[index.mod(AccountColours.size)]

/** An account's coloured dot. */
@Composable
fun AccountDot(colour: Int, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(accountColour(colour), CircleShape))
}

/** A small "● Work" tag: which of a network's accounts a chat or notification belongs to. */
@Composable
fun AccountTag(name: String, colour: Int, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .background(accountColour(colour).copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        AccountDot(colour, size = 6.dp)
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
