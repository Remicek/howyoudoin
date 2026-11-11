package com.devvalor.howyoudoing.settings.presentation.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.StarRate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.domain.app.AppPromotion
import com.devvalor.howyoudoing.core.domain.app.HiddenTips
import com.devvalor.howyoudoing.core.domain.app.RatePromptMemory
import com.devvalor.howyoudoing.core.domain.app.RatePromptRecord
import com.devvalor.howyoudoing.core.domain.app.Tip
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.common_not_now
import howyoudoin.feature.settings.presentation.generated.resources.rate_prompt_dont_show
import howyoudoin.feature.settings.presentation.generated.resources.rate_prompt_rate
import howyoudoin.feature.settings.presentation.generated.resources.rate_prompt_text
import howyoudoin.feature.settings.presentation.generated.resources.settings_rate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock

/** Networks connected on the phone before the app asks for a rating. */
const val RATE_PROMPT_NETWORKS = 3

/** How long "Not now" puts the question off. */
const val RATE_PROMPT_SNOOZE_MS = 14L * 24 * 60 * 60 * 1000

/**
 * Whether to ask for a rating now: once [RATE_PROMPT_NETWORKS] networks are connected (so the app has proved
 * useful), where there's a store to rate it in, and never again after "Don't show again" or after the store was
 * opened from the question.
 */
fun shouldAskForRating(
    linkedNetworks: Int,
    hidden: Boolean,
    record: RatePromptRecord,
    storeSupported: Boolean,
    nowMs: Long,
): Boolean = storeSupported &&
    linkedNetworks >= RATE_PROMPT_NETWORKS &&
    !hidden &&
    !record.rated &&
    nowMs >= record.snoozedUntilMs

/** The question shown over the inbox; [networks] is how many are connected (0 while it shouldn't show). */
class RatePromptViewModel(
    connectionMonitor: ConnectionMonitor,
    private val hiddenTips: HiddenTips,
    private val memory: RatePromptMemory,
    private val promotion: AppPromotion,
) : ViewModel() {

    val networks: StateFlow<Int> = combine(
        connectionMonitor.linkedNetworks,
        hiddenTips.observeHidden(Tip.RATE_APP),
        memory.observe(),
    ) { linked, hidden, record ->
        val ask = shouldAskForRating(
            linkedNetworks = linked.size,
            hidden = hidden,
            record = record,
            storeSupported = promotion.isSupported,
            nowMs = Clock.System.now().toEpochMilliseconds(),
        )
        if (ask) linked.size else 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), 0)

    fun rate() {
        promotion.openStorePage()
        viewModelScope.launch { memory.markRated() }
    }

    fun notNow() {
        viewModelScope.launch { memory.snooze(Clock.System.now().toEpochMilliseconds() + RATE_PROMPT_SNOOZE_MS) }
    }

    fun dontShowAgain() {
        viewModelScope.launch { hiddenTips.hide(Tip.RATE_APP) }
    }
}

/** Asks once for a rating on Google Play; "Don't show again" turns it off (Settings → Hidden tips brings it back). */
@Composable
fun RatePromptRoot(viewModel: RatePromptViewModel = koinViewModel()) {
    val networks by viewModel.networks.collectAsStateWithLifecycle()
    if (networks == 0) return
    AlertDialog(
        onDismissRequest = viewModel::notNow,
        icon = { Icon(Icons.Rounded.StarRate, contentDescription = null) },
        title = { Text(stringResource(Res.string.settings_rate)) },
        text = { Text(stringResource(Res.string.rate_prompt_text, networks)) },
        confirmButton = {
            TextButton(onClick = viewModel::rate) { Text(stringResource(Res.string.rate_prompt_rate)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::dontShowAgain) {
                    Text(stringResource(Res.string.rate_prompt_dont_show))
                }
                TextButton(onClick = viewModel::notNow) { Text(stringResource(Res.string.common_not_now)) }
            }
        },
    )
}
