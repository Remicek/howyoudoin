package com.devvalor.howyoudoing.settings.presentation.appearance

import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.appearance_graphite
import howyoudoin.feature.settings.presentation.generated.resources.appearance_graphite_sub
import howyoudoin.feature.settings.presentation.generated.resources.appearance_indigo
import howyoudoin.feature.settings.presentation.generated.resources.appearance_indigo_sub
import howyoudoin.feature.settings.presentation.generated.resources.appearance_lagoon
import howyoudoin.feature.settings.presentation.generated.resources.appearance_lagoon_sub
import howyoudoin.feature.settings.presentation.generated.resources.appearance_sample_in
import howyoudoin.feature.settings.presentation.generated.resources.appearance_sample_out
import howyoudoin.feature.settings.presentation.generated.resources.appearance_selected
import howyoudoin.feature.settings.presentation.generated.resources.appearance_sunset
import howyoudoin.feature.settings.presentation.generated.resources.appearance_sunset_sub
import howyoudoin.feature.settings.presentation.generated.resources.settings_appearance
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinTheme
import com.devvalor.howyoudoing.core.designsystem.theme.ThemeVariant
import com.devvalor.howyoudoing.settings.domain.AppTheme
import com.devvalor.howyoudoing.settings.domain.AppearanceRepository
import com.devvalor.howyoudoing.settings.presentation.components.SettingsScaffold
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** The saved theme as the design system's variant; null until it's read, so the app doesn't flash another look. */
class AppearanceViewModel(private val repository: AppearanceRepository) : ViewModel() {

    val variant = repository.observeTheme()
        .map { it.toVariant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun select(variant: ThemeVariant) {
        viewModelScope.launch { repository.setTheme(AppTheme.valueOf(variant.name)) }
    }
}

fun AppTheme.toVariant(): ThemeVariant = ThemeVariant.valueOf(name)

private val ThemeVariant.label
    @Composable get() = when (this) {
        ThemeVariant.INDIGO -> stringResource(Res.string.appearance_indigo)
        ThemeVariant.LAGOON -> stringResource(Res.string.appearance_lagoon)
        ThemeVariant.SUNSET -> stringResource(Res.string.appearance_sunset)
        ThemeVariant.GRAPHITE -> stringResource(Res.string.appearance_graphite)
    }

private val ThemeVariant.description
    @Composable get() = when (this) {
        ThemeVariant.INDIGO -> stringResource(Res.string.appearance_indigo_sub)
        ThemeVariant.LAGOON -> stringResource(Res.string.appearance_lagoon_sub)
        ThemeVariant.SUNSET -> stringResource(Res.string.appearance_sunset_sub)
        ThemeVariant.GRAPHITE -> stringResource(Res.string.appearance_graphite_sub)
    }

@Composable
fun AppearanceRoot(onBack: () -> Unit, viewModel: AppearanceViewModel = koinViewModel()) {
    val selected by viewModel.variant.collectAsStateWithLifecycle()
    SettingsScaffold(title = stringResource(Res.string.settings_appearance), onBack = onBack) {
        ThemeVariant.entries.forEach { variant ->
            item {
                ThemeOption(
                    variant = variant,
                    isSelected = variant == selected,
                    onClick = { viewModel.select(variant) })
            }
        }
    }
}

/** A small inbox drawn in the variant's own theme, so the choice shows what it looks like. */
@Composable
private fun ThemeOption(variant: ThemeVariant, isSelected: Boolean, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.primary
    HowYouDoinTheme(darkTheme = isSystemInDarkTheme(), variant = variant) {
        val colors = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm)
                .fillMaxWidth()
                .border(
                    if (isSelected) BorderStroke(2.dp, outline) else BorderStroke(1.dp, colors.outlineVariant),
                    MaterialTheme.shapes.large,
                )
                .background(colors.background, MaterialTheme.shapes.large)
                .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
                .padding(HowYouDoinSpacing.md),
            verticalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        variant.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onBackground
                    )
                    Text(
                        variant.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                if (isSelected) Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(Res.string.appearance_selected),
                    tint = colors.primary
                )
            }
            // Incoming and outgoing bubble, and a button, in the variant's colours and shapes
            Box(
                Modifier.fillMaxWidth(0.7f)
                    .background(colors.surfaceContainerHigh, MaterialTheme.shapes.medium)
                    .padding(HowYouDoinSpacing.sm)
            ) {
                Text(
                    stringResource(Res.string.appearance_sample_in),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.background(colors.primary, MaterialTheme.shapes.medium).padding(HowYouDoinSpacing.sm)
                ) {
                    Text(
                        stringResource(Res.string.appearance_sample_out),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onPrimary
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.xs)) {
                listOf(
                    colors.primary,
                    colors.primaryContainer,
                    colors.secondaryContainer,
                    colors.surfaceContainer
                ).forEach {
                    Box(
                        Modifier.size(18.dp).background(it, CircleShape)
                            .border(1.dp, colors.outlineVariant, CircleShape)
                    )
                }
            }
        }
    }
}
