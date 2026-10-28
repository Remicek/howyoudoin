package com.devvalor.howyoudoing.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devvalor.howyoudoing.core.designsystem.theme.brandColor
import com.devvalor.howyoudoing.core.designsystem.theme.logo
import com.devvalor.howyoudoing.core.designsystem.theme.monogram
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

@Composable
fun NetworkBadge(
    network: ChatNetwork,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    borderColor: Color = MaterialTheme.colorScheme.surface,
    enabled: Boolean = true,
) {
    val color = if (enabled) network.brandColor else MaterialTheme.colorScheme.outline
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .border(width = size / 9, color = borderColor, shape = CircleShape)
            .background(color, CircleShape),
    ) {
        val logo = network.logo
        if (logo != null) {
            Icon(
                logo,
                contentDescription = network.displayName,
                tint = Color.White,
                modifier = Modifier.size(size * 0.55f)
            )
        } else {
            Text(
                text = network.monogram,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.45f).sp,
                maxLines = 1,
            )
        }
    }
}
