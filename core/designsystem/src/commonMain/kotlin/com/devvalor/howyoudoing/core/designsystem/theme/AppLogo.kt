package com.devvalor.howyoudoing.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The app's colours: the logo's orange, and the dark brown of its face. */
val LogoOrange = Color(0xFFFF7A45)
private val LogoFace = Color(0xFF3A1E12)

// The winking chat bubble, as in docs/logo/howyoudoin-logo.svg and the launcher icon
private const val BUBBLE = "M26 24h68a14 14 0 0 1 14 14v34a14 14 0 0 1-14 14H58L38 102V86H26a14 14 0 0 1-14-14V38" +
    "a14 14 0 0 1 14-14z"
private const val FACE = "M36 49Q45 40.5 54 49A2.75 2.75 0 0 1 50 53Q45 47.5 40 53A2.75 2.75 0 0 1 36 49z" +
    "M71.5 50a5.5 5.5 0 1 0 11 0a5.5 5.5 0 1 0-11 0z" +
    "M39.8 66Q60 82.5 80.2 66A2.75 2.75 0 0 0 75.8 62Q60 75.5 44.2 62A2.75 2.75 0 0 0 39.8 66z"
private const val SQUARE = "M28 0h64a28 28 0 0 1 28 28v64a28 28 0 0 1-28 28H28A28 28 0 0 1 0 92V28A28 28 0 0 1 28 0z"

/** The full app icon: the winking bubble on its orange rounded square. */
val AppLogo: ImageVector by lazy {
    ImageVector.Builder(
        name = "HowYouDoin",
        defaultWidth = 120.dp,
        defaultHeight = 120.dp,
        viewportWidth = 120f,
        viewportHeight = 120f
    )
        .addPath(pathData = addPathNodes(SQUARE), fill = SolidColor(LogoOrange))
        .addPath(pathData = addPathNodes(BUBBLE), fill = SolidColor(Color.White))
        .addPath(pathData = addPathNodes(FACE), fill = SolidColor(LogoFace))
        .build()
}
