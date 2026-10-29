package com.devvalor.howyoudoing.core.designsystem.theme

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The brand colours: the logo's electric blue to violet, and the midnight navy of its face. */
val BrandBlue = Color(0xFF276BFF)
val BrandViolet = Color(0xFF7138F5)
private val LogoFace = Color(0xFF101936)

/** The small bright dots under the bubble: the networks coming together. */
private val Accents = listOf(Color(0xFF20C86B), Color(0xFFFFC23D), Color(0xFFFF5C93))

// The winking chat bubble, as in docs/logo/howyoudoin-logo.svg and the launcher icon
private const val BUBBLE = "M26 24h68a14 14 0 0 1 14 14v34a14 14 0 0 1-14 14H58L38 102V86H26a14 14 0 0 1-14-14V38" +
    "a14 14 0 0 1 14-14z"
private const val FACE = "M36 49Q45 40.5 54 49A2.75 2.75 0 0 1 50 53Q45 47.5 40 53A2.75 2.75 0 0 1 36 49z" +
    "M71.5 50a5.5 5.5 0 1 0 11 0a5.5 5.5 0 1 0-11 0z" +
    "M39.8 66Q60 82.5 80.2 66A2.75 2.75 0 0 0 75.8 62Q60 75.5 44.2 62A2.75 2.75 0 0 0 39.8 66z"
private fun dot(centerX: Int) = "M${centerX - 4.5f} 97a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0-9 0z"
private const val FIRST_DOT_X = 74
private const val DOT_GAP = 12
private const val SQUARE = "M28 0h64a28 28 0 0 1 28 28v64a28 28 0 0 1-28 28H28A28 28 0 0 1 0 92V28A28 28 0 0 1 28 0z"

/** The full app icon: the winking bubble on its blue-to-violet rounded square. */
val AppLogo: ImageVector by lazy {
    ImageVector.Builder(
        name = "HowYouDoin",
        defaultWidth = 120.dp,
        defaultHeight = 120.dp,
        viewportWidth = 120f,
        viewportHeight = 120f
    )
        .addPath(
            pathData = addPathNodes(SQUARE),
            fill = Brush.linearGradient(listOf(BrandBlue, BrandViolet), Offset.Zero, Offset(120f, 120f)),
        )
        .addPath(pathData = addPathNodes(BUBBLE), fill = SolidColor(Color.White))
        .addPath(pathData = addPathNodes(FACE), fill = SolidColor(LogoFace))
        .apply {
            Accents.forEachIndexed { i, color ->
                addPath(pathData = addPathNodes(dot(FIRST_DOT_X + i * DOT_GAP)), fill = SolidColor(color))
            }
        }
        .build()
}

/** "howyoudoin" as the app writes its name: "howyou" in the text colour, "doin" in the logo's blue to violet. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, fontSize: Int = 22) {
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append("howyou") }
        withStyle(SpanStyle(brush = Brush.horizontalGradient(listOf(BrandBlue, BrandViolet)))) { append("doin") }
    }
    Text(
        text,
        style = TextStyle(fontSize = fontSize.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        modifier = modifier,
    )
}
