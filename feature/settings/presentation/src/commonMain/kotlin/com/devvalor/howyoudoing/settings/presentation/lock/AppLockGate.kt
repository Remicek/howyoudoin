package com.devvalor.howyoudoing.settings.presentation.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/**
 * Shows [content] only when the app is unlocked (or the lock is off). While locked the PIN pad covers it, and the
 * content is hidden from accessibility services too. [isActive] is false where there's nothing to protect (e.g.
 * signed out).
 */
@Composable
fun AppLockGate(
    isActive: Boolean,
    viewModel: AppLockViewModel = koinViewModel(),
    content: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onAction(AppLockAction.OnAppHidden)
                Lifecycle.Event.ON_START -> viewModel.onAction(AppLockAction.OnAppShown)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isCovered = isActive && (!state.isReady || state.isLocked)
    Box(Modifier.fillMaxSize()) {
        Box(if (isCovered) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
            content()
        }
        if (isCovered) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    // Swallows touches meant for the content underneath
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .safeDrawingPadding(),
            ) {
                if (state.isReady) {
                    PinPad(
                        title = "Enter your PIN",
                        pinLength = state.pinLength,
                        message = state.message,
                        isError = state.isError,
                        enabled = !state.isChecking && state.waitSeconds == 0L,
                        onDigit = { viewModel.onAction(AppLockAction.OnDigit(it)) },
                        onDelete = { viewModel.onAction(AppLockAction.OnDelete) },
                        onSubmit = { viewModel.onAction(AppLockAction.OnSubmit) },
                        onBiometricClick = if (state.canUseBiometric) ({ viewModel.onAction(AppLockAction.OnBiometricClick) }) else null,
                    )
                }
            }
        }
    }
}
