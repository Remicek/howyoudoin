@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.devvalor.howyoudoing.settings.presentation.crash

import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.common_delete
import howyoudoin.feature.settings.presentation.generated.resources.crash_intro
import howyoudoin.feature.settings.presentation.generated.resources.crash_last
import howyoudoin.feature.settings.presentation.generated.resources.crash_none
import howyoudoin.feature.settings.presentation.generated.resources.crash_offer
import howyoudoin.feature.settings.presentation.generated.resources.crash_offer_sub
import howyoudoin.feature.settings.presentation.generated.resources.crash_share
import howyoudoin.feature.settings.presentation.generated.resources.settings_crash_reports
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.settings.domain.CrashReport
import com.devvalor.howyoudoing.settings.domain.CrashReports
import com.devvalor.howyoudoing.settings.presentation.components.SettingsScaffold
import com.devvalor.howyoudoing.settings.presentation.components.SettingsSectionHeader
import com.devvalor.howyoudoing.settings.presentation.components.SettingsSwitchRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Instant

data class CrashReportsState(
    val lastReport: CrashReport? = null,
    val offersAfterCrash: Boolean = true,
)

sealed interface CrashReportsAction {
    data class OnOffersChange(val offers: Boolean) : CrashReportsAction
    data object OnShareClick : CrashReportsAction
    data object OnDeleteClick : CrashReportsAction
    data object OnBackClick : CrashReportsAction
}

class CrashReportsViewModel(private val reports: CrashReports) : ViewModel() {

    val state = combine(reports.observeLastReport(), reports.observeOffersAfterCrash()) { last, offers ->
        CrashReportsState(lastReport = last, offersAfterCrash = offers)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), CrashReportsState())

    fun onAction(action: CrashReportsAction) {
        when (action) {
            is CrashReportsAction.OnOffersChange -> viewModelScope.launch { reports.setOffersAfterCrash(action.offers) }
            CrashReportsAction.OnShareClick -> state.value.lastReport?.let(reports::share)
            CrashReportsAction.OnDeleteClick -> viewModelScope.launch { reports.delete() }
            CrashReportsAction.OnBackClick -> Unit
        }
    }
}

@Composable
fun CrashReportsRoot(
    onBack: () -> Unit,
    viewModel: CrashReportsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CrashReportsScreen(state) { action ->
        when (action) {
            CrashReportsAction.OnBackClick -> onBack()
            else -> viewModel.onAction(action)
        }
    }
}

@Composable
fun CrashReportsScreen(state: CrashReportsState, onAction: (CrashReportsAction) -> Unit) {
    SettingsScaffold(
        title = stringResource(Res.string.settings_crash_reports),
        onBack = { onAction(CrashReportsAction.OnBackClick) }) {
        item {
            Text(
                stringResource(Res.string.crash_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
            )
        }
        item {
            SettingsSwitchRow(
                title = stringResource(Res.string.crash_offer),
                subtitle = stringResource(Res.string.crash_offer_sub),
                icon = Icons.Rounded.BugReport,
                checked = state.offersAfterCrash,
                onCheckedChange = { onAction(CrashReportsAction.OnOffersChange(it)) },
            )
        }
        item { SettingsSectionHeader(stringResource(Res.string.crash_last)) }
        val report = state.lastReport
        if (report == null) {
            item {
                Text(
                    stringResource(Res.string.crash_none),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
                )
            }
        } else {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = HowYouDoinSpacing.md),
                ) {
                    Column(Modifier.padding(HowYouDoinSpacing.md)) {
                        Text(formatTime(report.timeMillis), style = MaterialTheme.typography.titleSmall)
                        SelectionContainer {
                            Text(
                                report.text,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                maxLines = 40,
                                modifier = Modifier.padding(top = HowYouDoinSpacing.sm)
                                    .horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm),
                    modifier = Modifier.padding(HowYouDoinSpacing.md),
                ) {
                    FilledTonalButton(onClick = { onAction(CrashReportsAction.OnShareClick) }) {
                        Text(stringResource(Res.string.crash_share))
                    }
                    OutlinedButton(onClick = { onAction(CrashReportsAction.OnDeleteClick) }) {
                        Text(stringResource(Res.string.common_delete))
                    }
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val time = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${time.year}-${two(time.month.ordinal + 1)}-${two(time.day)} ${two(time.hour)}:${two(time.minute)}"
}
