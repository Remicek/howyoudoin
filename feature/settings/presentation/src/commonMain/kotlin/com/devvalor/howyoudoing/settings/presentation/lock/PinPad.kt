package com.devvalor.howyoudoing.settings.presentation.lock

import org.jetbrains.compose.resources.pluralStringResource
import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.common_delete
import howyoudoin.feature.settings.presentation.generated.resources.pin_delete_digit
import howyoudoin.feature.settings.presentation.generated.resources.pin_digits_entered
import howyoudoin.feature.settings.presentation.generated.resources.pin_done
import howyoudoin.feature.settings.presentation.generated.resources.pin_use_fingerprint
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing

const val PIN_MIN_LENGTH = 4
const val PIN_MAX_LENGTH = 8

/**
 * Title, dots for the digits typed so far, an optional message, and a number pad. [onBiometricClick] adds a
 * fingerprint key next to 0.
 */
@Composable
fun PinPad(
    title: String,
    pinLength: Int,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    onBiometricClick: (() -> Unit)? = null,
) {
    val pinDescription = pluralStringResource(Res.plurals.pin_digits_entered, pinLength, pinLength)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(HowYouDoinSpacing.lg))
        Row(
            horizontalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm),
            modifier = Modifier.height(16.dp).semantics { contentDescription = pinDescription },
        ) {
            repeat(maxOf(pinLength, PIN_MIN_LENGTH)) { index ->
                Box(
                    Modifier.size(14.dp).background(
                        if (index < pinLength) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape,
                    )
                )
            }
        }
        Spacer(Modifier.height(HowYouDoinSpacing.md))
        Text(
            message.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            minLines = 2,
            modifier = Modifier.padding(horizontal = HowYouDoinSpacing.lg),
        )
        Spacer(Modifier.height(HowYouDoinSpacing.md))
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.lg)) {
                row.forEach { digit -> DigitKey(digit, enabled, onDigit) }
            }
            Spacer(Modifier.height(HowYouDoinSpacing.md))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                if (onBiometricClick != null) {
                    IconButton(onClick = onBiometricClick, enabled = enabled, modifier = Modifier.size(KEY_SIZE)) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = stringResource(Res.string.pin_use_fingerprint), modifier = Modifier.size(32.dp))
                    }
                } else {
                    IconButton(onClick = onDelete, enabled = enabled && pinLength > 0, modifier = Modifier.size(KEY_SIZE)) {
                        Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = stringResource(Res.string.pin_delete_digit))
                    }
                }
            }
            DigitKey(0, enabled, onDigit)
            Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                FilledTonalIconButton(
                    onClick = onSubmit,
                    enabled = enabled && pinLength >= PIN_MIN_LENGTH,
                    modifier = Modifier.size(KEY_SIZE),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = stringResource(Res.string.pin_done))
                }
            }
        }
        if (onBiometricClick != null) {
            TextButton(onClick = onDelete, enabled = enabled && pinLength > 0) { Text(stringResource(Res.string.common_delete)) }
        }
    }
}

@Composable
private fun DigitKey(digit: Int, enabled: Boolean, onDigit: (Int) -> Unit) {
    TextButton(onClick = { onDigit(digit) }, enabled = enabled, shape = CircleShape, modifier = Modifier.size(KEY_SIZE)) {
        Text(digit.toString(), fontSize = 28.sp, fontWeight = FontWeight.Medium)
    }
}

private val KEY_SIZE = 72.dp
