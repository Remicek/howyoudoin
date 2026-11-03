package com.devvalor.howyoudoing.core.presentation

import androidx.compose.runtime.Composable

/**
 * Keeps the screen in portrait while this is in the composition, on phones only (tablets keep rotating), and puts
 * back the previous orientation after. For screens that don't lay out well sideways, like the login web views.
 */
@Composable
expect fun LockPortraitOnPhones()
