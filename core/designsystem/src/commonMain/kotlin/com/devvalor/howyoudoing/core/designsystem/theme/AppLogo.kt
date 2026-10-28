package com.devvalor.howyoudoing.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The brand colours: the logo's blue to violet, and the midnight navy behind it. */
val BrandBlue = Color(0xFF2F6BFF)
val BrandViolet = Color(0xFF8B3DF6)
val BrandNavy = Color(0xFF101936)

/** The three strokes beside the bubble: the networks coming together. */
private val Accents = listOf(
    Color(0xFF22C55E) to "M91 21l5-11",
    Color(0xFFFACC15) to "M99 28l10-6",
    Color(0xFFF43F8E) to "M102 39l11-1",
)

// The chat bubble with a smile, as in docs/logo/howyoudoin-logo.svg and the launcher icon (a 120 by 120 drawing)
private const val BUBBLE = "M45 30h28a22 22 0 0 1 22 22v14a22 22 0 0 1-22 22H48l-17 13.5V86.5a22 22 0 0 1-8-17V52" +
    "a22 22 0 0 1 22-22z"
private const val SMILE = "M44 60q15 15 30 0"
private const val SQUARE = "M22 -12h76a34 34 0 0 1 34 34v76a34 34 0 0 1-34 34H22A34 34 0 0 1-12 98V22" +
    "A34 34 0 0 1 22-12z"

private const val BUBBLE_STROKE = 11f
private const val SMILE_STROKE = 8.5f
private const val ACCENT_STROKE = 6.5f

private val Gradient = Brush.linearGradient(listOf(BrandBlue, BrandViolet), Offset.Zero, Offset(120f, 120f))

private fun ImageVector.Builder.mark(smile: Brush): ImageVector.Builder {
    addPath(
        pathData = addPathNodes(BUBBLE),
        stroke = Gradient,
        strokeLineWidth = BUBBLE_STROKE,
        strokeLineJoin = StrokeJoin.Round,
    )
    addPath(
        pathData = addPathNodes(SMILE),
        stroke = smile,
        strokeLineWidth = SMILE_STROKE,
        strokeLineCap = StrokeCap.Round,
    )
    Accents.forEach { (color, path) ->
        addPath(
            pathData = addPathNodes(path),
            stroke = SolidColor(color),
            strokeLineWidth = ACCENT_STROKE,
            strokeLineCap = StrokeCap.Round,
        )
    }
    return this
}

/** The app icon: the mark on its midnight navy rounded square, with a white smile. */
val AppLogo: ImageVector by lazy {
    ImageVector.Builder(
        name = "HowYouDoin",
        defaultWidth = 144.dp,
        defaultHeight = 144.dp,
        viewportWidth = 144f,
        viewportHeight = 144f
    )
        .addGroup(translationX = 12f, translationY = 12f)
        .apply { addPath(pathData = addPathNodes(SQUARE), fill = SolidColor(BrandNavy)) }
        .mark(smile = SolidColor(Color.White))
        .clearGroup()
        .build()
}

/** The mark on its own (transparent), for small places like the inbox's top bar. */
val AppMark: ImageVector by lazy {
    ImageVector.Builder(
        name = "HowYouDoinMark",
        defaultWidth = 120.dp,
        defaultHeight = 120.dp,
        viewportWidth = 120f,
        viewportHeight = 120f
    )
        .mark(smile = Gradient)
        .build()
}

/** "howyoudoin" as the logo writes it: "howyou" in the text colour, "doin" in the brand's blue to violet. */
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
