package com.devvalor.howyoudoing.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun HowYouDoinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    variant: ThemeVariant = ThemeVariant.INDIGO,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = variant.colorScheme(darkTheme),
        shapes = variant.shapes(),
        content = content,
    )
}
