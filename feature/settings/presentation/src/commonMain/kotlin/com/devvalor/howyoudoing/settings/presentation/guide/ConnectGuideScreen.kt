package com.devvalor.howyoudoing.settings.presentation.guide

import howyoudoin.feature.settings.presentation.generated.resources.guide_connect
import howyoudoin.feature.settings.presentation.generated.resources.guide_intro
import howyoudoin.feature.settings.presentation.generated.resources.guide_unofficial
import howyoudoin.feature.settings.presentation.generated.resources.settings_guide
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.guide_meta_name
import howyoudoin.feature.settings.presentation.generated.resources.guide_whatsapp_steps
import howyoudoin.feature.settings.presentation.generated.resources.guide_meta_steps
import howyoudoin.feature.settings.presentation.generated.resources.guide_telegram_steps
import howyoudoin.feature.settings.presentation.generated.resources.guide_signal_steps
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.settings.presentation.components.SettingsScaffold

/** One network's way of connecting, in the user's words. */
private data class NetworkGuide(val nameRes: StringResource?, val brand: String?, val steps: StringResource) {
    val name: String @Composable get() = nameRes?.let { stringResource(it) } ?: brand.orEmpty()
}

private val guides = listOf(
    NetworkGuide(null, "WhatsApp", Res.string.guide_whatsapp_steps),
    NetworkGuide(Res.string.guide_meta_name, null, Res.string.guide_meta_steps),
    NetworkGuide(null, "Telegram", Res.string.guide_telegram_steps),
    NetworkGuide(null, "Signal", Res.string.guide_signal_steps),
)

@Composable
fun ConnectGuideRoot(onNetworksClick: () -> Unit, onBack: () -> Unit) {
    SettingsScaffold(title = stringResource(Res.string.settings_guide), onBack = onBack) {
        item {
            Paragraph(
                stringResource(Res.string.guide_intro),
            )
        }
        guides.forEach { guide ->
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm)) {
                    Text(
                        guide.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(HowYouDoinSpacing.xs))
                    Text(stringResource(guide.steps), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Paragraph(
                stringResource(Res.string.guide_unofficial),
            )
        }
        item {
            FilledTonalButton(
                onClick = onNetworksClick,
                modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.md),
            ) { Text(stringResource(Res.string.guide_connect)) }
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
    )
}
