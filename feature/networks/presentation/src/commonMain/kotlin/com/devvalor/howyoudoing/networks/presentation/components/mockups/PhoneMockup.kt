package com.devvalor.howyoudoing.networks.presentation.components.mockups

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Accent colours for the illustrated WhatsApp screens (illustrations, not real screenshots). */
internal object MockColors {
    val header = Color(0xFF008069)
    val accent = Color(0xFF25D366)
    val highlight = Color(0xFF25D366)
    val scanner = Color(0xFF1F2C34)
    val placeholder = Color(0xFFD9DEE1)
}

/** A simplified phone outline that hosts an illustrated screen. */
@Composable
fun PhoneMockup(
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .width(width)
            .height(width * 2f)
            .border(6.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), RoundedCornerShape(30.dp))
            .padding(6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White),
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
        // Camera notch
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .size(8.dp)
                .background(Color.Black.copy(alpha = 0.7f), CircleShape),
        )
    }
}

/** Grey bar standing in for text we don't need to show (names, chat previews...). */
@Composable
internal fun Placeholder(width: Dp, height: Dp = 8.dp, color: Color = MockColors.placeholder) {
    Box(Modifier.width(width).height(height).background(color, RoundedCornerShape(4.dp)))
}

/** Pulsing outline + tap marker on the element the user should press. */
@Composable
internal fun Highlighted(
    modifier: Modifier = Modifier,
    tapMarkerColor: Color = Color(0xFF263238),
    content: @Composable () -> Unit,
) {
    val pulse by rememberInfiniteTransition(label = "highlight").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(modifier = modifier) {
        Box(
            Modifier
                .border(2.dp, MockColors.highlight.copy(alpha = pulse), RoundedCornerShape(10.dp))
                .padding(2.dp),
        ) { content() }
        Icon(
            Icons.Rounded.TouchApp,
            contentDescription = null,
            tint = tapMarkerColor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 6.dp, y = 14.dp)
                .size(26.dp)
                .graphicsLayer { scaleX = 0.9f + pulse * 0.1f; scaleY = 0.9f + pulse * 0.1f },
        )
    }
}
