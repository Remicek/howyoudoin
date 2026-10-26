package com.devvalor.howyoudoing.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The looks the user can pick in Settings → Appearance: colours for light and dark, and how round corners are. */
enum class ThemeVariant {
    /** Calm indigo, the original look. */
    INDIGO,
    /** Fresh teal with soft, very round shapes. */
    LAGOON,
    /** Warm coral and cream. */
    SUNSET,
    /** Black and white with crisp corners; true black when dark, so network colours stand out. */
    GRAPHITE,
}

internal fun ThemeVariant.colorScheme(dark: Boolean): ColorScheme = when (this) {
    ThemeVariant.INDIGO -> if (dark) IndigoDark else IndigoLight
    ThemeVariant.LAGOON -> if (dark) LagoonDark else LagoonLight
    ThemeVariant.SUNSET -> if (dark) SunsetDark else SunsetLight
    ThemeVariant.GRAPHITE -> if (dark) GraphiteDark else GraphiteLight
}

internal fun ThemeVariant.shapes(): Shapes = when (this) {
    ThemeVariant.INDIGO -> shapes(8, 12, 20)
    ThemeVariant.LAGOON -> shapes(14, 20, 28)
    ThemeVariant.SUNSET -> shapes(10, 16, 24)
    ThemeVariant.GRAPHITE -> shapes(4, 6, 10)
}

private fun shapes(small: Int, medium: Int, large: Int) = Shapes(
    small = RoundedCornerShape(small.dp),
    medium = RoundedCornerShape(medium.dp),
    large = RoundedCornerShape(large.dp),
)

/** The tones every variant sets; the rest come from Material's defaults. */
private class Tones(
    val primary: Long, val onPrimary: Long, val primaryContainer: Long, val onPrimaryContainer: Long,
    val secondary: Long, val onSecondary: Long, val secondaryContainer: Long, val onSecondaryContainer: Long,
    val background: Long, val onBackground: Long,
    val surfaceVariant: Long, val onSurfaceVariant: Long, val outline: Long, val outlineVariant: Long,
    val lowest: Long, val low: Long, val container: Long, val high: Long, val highest: Long,
)

private fun light(t: Tones) = lightColorScheme(
    primary = Color(t.primary), onPrimary = Color(t.onPrimary),
    primaryContainer = Color(t.primaryContainer), onPrimaryContainer = Color(t.onPrimaryContainer),
    secondary = Color(t.secondary), onSecondary = Color(t.onSecondary),
    secondaryContainer = Color(t.secondaryContainer), onSecondaryContainer = Color(t.onSecondaryContainer),
    error = LightError, onError = LightOnError, errorContainer = LightErrorContainer, onErrorContainer = LightOnErrorContainer,
    background = Color(t.background), onBackground = Color(t.onBackground),
    surface = Color(t.background), onSurface = Color(t.onBackground),
    surfaceVariant = Color(t.surfaceVariant), onSurfaceVariant = Color(t.onSurfaceVariant),
    outline = Color(t.outline), outlineVariant = Color(t.outlineVariant),
    surfaceContainerLowest = Color(t.lowest), surfaceContainerLow = Color(t.low), surfaceContainer = Color(t.container),
    surfaceContainerHigh = Color(t.high), surfaceContainerHighest = Color(t.highest),
)

private fun dark(t: Tones) = darkColorScheme(
    primary = Color(t.primary), onPrimary = Color(t.onPrimary),
    primaryContainer = Color(t.primaryContainer), onPrimaryContainer = Color(t.onPrimaryContainer),
    secondary = Color(t.secondary), onSecondary = Color(t.onSecondary),
    secondaryContainer = Color(t.secondaryContainer), onSecondaryContainer = Color(t.onSecondaryContainer),
    error = DarkError, onError = DarkOnError, errorContainer = DarkErrorContainer, onErrorContainer = DarkOnErrorContainer,
    background = Color(t.background), onBackground = Color(t.onBackground),
    surface = Color(t.background), onSurface = Color(t.onBackground),
    surfaceVariant = Color(t.surfaceVariant), onSurfaceVariant = Color(t.onSurfaceVariant),
    outline = Color(t.outline), outlineVariant = Color(t.outlineVariant),
    surfaceContainerLowest = Color(t.lowest), surfaceContainerLow = Color(t.low), surfaceContainer = Color(t.container),
    surfaceContainerHigh = Color(t.high), surfaceContainerHighest = Color(t.highest),
)

private val IndigoLight = light(
    Tones(
        0xFF5B5BD6, 0xFFFFFFFF, 0xFFE4E3FF, 0xFF14135E, 0xFF5D5C72, 0xFFFFFFFF, 0xFFE3E0F9, 0xFF1A1A2C,
        0xFFFCFBFF, 0xFF1B1B21, 0xFFE4E1EC, 0xFF5F5D6B, 0xFF787680, 0xFFDCD9E4,
        0xFFFFFFFF, 0xFFF5F3FB, 0xFFEFEDF6, 0xFFE9E7F0, 0xFFE4E1EA,
    )
)
private val IndigoDark = dark(
    Tones(
        0xFF9B9BF5, 0xFF1F1F7A, 0xFF3B3BA8, 0xFFE4E3FF, 0xFFC7C4DD, 0xFF2F2F42, 0xFF454559, 0xFFE3E0F9,
        0xFF121218, 0xFFE4E1E9, 0xFF47464F, 0xFFC8C5D0, 0xFF928F9A, 0xFF47464F,
        0xFF0D0D12, 0xFF1B1B21, 0xFF1F1F25, 0xFF29292F, 0xFF34343A,
    )
)

private val LagoonLight = light(
    Tones(
        0xFF006B5F, 0xFFFFFFFF, 0xFF9FF2E2, 0xFF00201C, 0xFF4A635E, 0xFFFFFFFF, 0xFFCCE8E1, 0xFF05201C,
        0xFFF6FBF9, 0xFF171D1B, 0xFFDAE5E1, 0xFF3F4946, 0xFF6F7976, 0xFFBEC9C5,
        0xFFFFFFFF, 0xFFF0F5F3, 0xFFEAEFED, 0xFFE4E9E7, 0xFFDEE4E1,
    )
)
private val LagoonDark = dark(
    Tones(
        0xFF83D5C6, 0xFF003731, 0xFF005048, 0xFF9FF2E2, 0xFFB1CCC5, 0xFF1C3530, 0xFF334B47, 0xFFCCE8E1,
        0xFF0E1513, 0xFFDEE4E1, 0xFF3F4946, 0xFFBEC9C5, 0xFF899390, 0xFF3F4946,
        0xFF090F0E, 0xFF171D1B, 0xFF1B211F, 0xFF252B2A, 0xFF303634,
    )
)

private val SunsetLight = light(
    Tones(
        0xFF9C4127, 0xFFFFFFFF, 0xFFFFDBD0, 0xFF3A0B00, 0xFF77574D, 0xFFFFFFFF, 0xFFFFDBD0, 0xFF2C160E,
        0xFFFFF8F6, 0xFF231917, 0xFFF5DED7, 0xFF53433F, 0xFF85736E, 0xFFD8C2BC,
        0xFFFFFFFF, 0xFFFFF1ED, 0xFFFCEAE5, 0xFFF7E4DF, 0xFFF1DFDA,
    )
)
private val SunsetDark = dark(
    Tones(
        0xFFFFB59F, 0xFF5E1702, 0xFF7E2B12, 0xFFFFDBD0, 0xFFE7BDB1, 0xFF442A21, 0xFF5D4036, 0xFFFFDBD0,
        0xFF1A110F, 0xFFF1DFDA, 0xFF53433F, 0xFFD8C2BC, 0xFFA08C87, 0xFF53433F,
        0xFF140C0A, 0xFF231917, 0xFF271D1B, 0xFF322825, 0xFF3D322F,
    )
)

private val GraphiteLight = light(
    Tones(
        0xFF1F1F1F, 0xFFFFFFFF, 0xFFE3E3E3, 0xFF1A1A1A, 0xFF5E5E5E, 0xFFFFFFFF, 0xFFE8E8E8, 0xFF1B1B1B,
        0xFFFFFFFF, 0xFF111111, 0xFFE6E6E6, 0xFF5C5C5C, 0xFF7A7A7A, 0xFFDDDDDD,
        0xFFFFFFFF, 0xFFF6F6F6, 0xFFF0F0F0, 0xFFEAEAEA, 0xFFE4E4E4,
    )
)
private val GraphiteDark = dark(
    Tones(
        0xFFF2F2F2, 0xFF111111, 0xFF3A3A3A, 0xFFF2F2F2, 0xFFC6C6C6, 0xFF2E2E2E, 0xFF333333, 0xFFE8E8E8,
        0xFF000000, 0xFFEDEDED, 0xFF2E2E2E, 0xFFB5B5B5, 0xFF8A8A8A, 0xFF2E2E2E,
        0xFF000000, 0xFF0E0E0E, 0xFF141414, 0xFF1C1C1C, 0xFF262626,
    )
)
