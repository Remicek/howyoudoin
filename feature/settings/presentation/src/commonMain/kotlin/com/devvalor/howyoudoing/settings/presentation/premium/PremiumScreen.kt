@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.devvalor.howyoudoing.settings.presentation.premium

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import com.devvalor.howyoudoing.core.domain.app.AppPromotion
import com.devvalor.howyoudoing.core.domain.premium.PremiumStore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.domain.ondevice.MAX_ACCOUNTS_PER_NETWORK
import com.devvalor.howyoudoing.core.domain.premium.CallMinutes
import com.devvalor.howyoudoing.core.domain.premium.Plans
import com.devvalor.howyoudoing.settings.presentation.components.SettingsRow
import com.devvalor.howyoudoing.settings.presentation.components.SettingsScaffold
import com.devvalor.howyoudoing.settings.presentation.components.SettingsSectionHeader
import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.premium_active_body
import howyoudoin.feature.settings.presentation.generated.resources.premium_audio_left
import howyoudoin.feature.settings.presentation.generated.resources.premium_benefit_accounts
import howyoudoin.feature.settings.presentation.generated.resources.premium_benefit_audio
import howyoudoin.feature.settings.presentation.generated.resources.premium_benefit_length
import howyoudoin.feature.settings.presentation.generated.resources.premium_benefit_quality
import howyoudoin.feature.settings.presentation.generated.resources.premium_benefit_video
import howyoudoin.feature.settings.presentation.generated.resources.premium_duration_h
import howyoudoin.feature.settings.presentation.generated.resources.premium_duration_hm
import howyoudoin.feature.settings.presentation.generated.resources.premium_duration_m
import howyoudoin.feature.settings.presentation.generated.resources.premium_failed
import howyoudoin.feature.settings.presentation.generated.resources.premium_free_body
import howyoudoin.feature.settings.presentation.generated.resources.premium_guests_free
import howyoudoin.feature.settings.presentation.generated.resources.premium_includes
import howyoudoin.feature.settings.presentation.generated.resources.premium_manage
import howyoudoin.feature.settings.presentation.generated.resources.premium_minutes_failed
import howyoudoin.feature.settings.presentation.generated.resources.premium_minutes_header
import howyoudoin.feature.settings.presentation.generated.resources.premium_no_play
import howyoudoin.feature.settings.presentation.generated.resources.premium_not_offered
import howyoudoin.feature.settings.presentation.generated.resources.premium_open_play
import howyoudoin.feature.settings.presentation.generated.resources.premium_try_again
import howyoudoin.feature.settings.presentation.generated.resources.premium_pending
import howyoudoin.feature.settings.presentation.generated.resources.premium_resets_in
import howyoudoin.feature.settings.presentation.generated.resources.premium_restore
import howyoudoin.feature.settings.presentation.generated.resources.premium_subscribe
import howyoudoin.feature.settings.presentation.generated.resources.premium_thanks
import howyoudoin.feature.settings.presentation.generated.resources.premium_then_price
import howyoudoin.feature.settings.presentation.generated.resources.premium_title_active
import howyoudoin.feature.settings.presentation.generated.resources.premium_title_free
import howyoudoin.feature.settings.presentation.generated.resources.premium_top_up_added
import howyoudoin.feature.settings.presentation.generated.resources.premium_top_up_body
import howyoudoin.feature.settings.presentation.generated.resources.premium_top_up_button
import howyoudoin.feature.settings.presentation.generated.resources.premium_top_up_header
import howyoudoin.feature.settings.presentation.generated.resources.premium_try_free
import howyoudoin.feature.settings.presentation.generated.resources.premium_video_bought
import howyoudoin.feature.settings.presentation.generated.resources.premium_video_left
import howyoudoin.feature.settings.presentation.generated.resources.settings_premium
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock

@Composable
fun PremiumRoot(onBack: () -> Unit, viewModel: PremiumViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    PremiumScreen(state) { action ->
        when (action) {
            PremiumAction.OnBackClick -> onBack()
            PremiumAction.OnManageClick -> uriHandler.openUri(viewModel.manageUrl)
            PremiumAction.OnOpenPlayClick -> uriHandler.openUri(AppPromotion.PLAY_STORE_URL)
            else -> viewModel.onAction(action)
        }
    }
}

@Composable
fun PremiumScreen(state: PremiumState, onAction: (PremiumAction) -> Unit) {
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(MESSAGE_MS)
            onAction(PremiumAction.OnMessageShown)
        }
    }
    SettingsScaffold(
        title = stringResource(Res.string.settings_premium),
        onBack = { onAction(PremiumAction.OnBackClick) },
    ) {
        state.message?.let { message -> item { MessageCard(message) } }
        // The way to buy comes first, with what Premium gives right under it
        item { PlanCard(state, onAction) }
        if (!state.isPremium) {
            item { SettingsSectionHeader(stringResource(Res.string.premium_includes)) }
            item { Benefits() }
        }
        if (state.hasCalls) {
            item { SettingsSectionHeader(stringResource(Res.string.premium_minutes_header)) }
            item { Minutes(state) }
        }
        val topUp = state.topUp
        if (state.hasCalls && topUp != null) {
            item { SettingsSectionHeader(stringResource(Res.string.premium_top_up_header)) }
            item {
                Column(Modifier.padding(horizontal = HowYouDoinSpacing.md)) {
                    Text(
                        stringResource(Res.string.premium_top_up_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { onAction(PremiumAction.OnTopUpClick) },
                        enabled = !state.isBuying,
                        modifier = Modifier.padding(top = HowYouDoinSpacing.sm),
                    ) {
                        Text(stringResource(Res.string.premium_top_up_button, Plans.TOP_UP_VIDEO_HOURS, topUp.price))
                    }
                }
            }
        }
        item {
            TextButton(
                onClick = { onAction(PremiumAction.OnRestoreClick) },
                modifier = Modifier.padding(horizontal = HowYouDoinSpacing.sm, vertical = HowYouDoinSpacing.md),
            ) { Text(stringResource(Res.string.premium_restore)) }
        }
    }
}

@Composable
private fun MessageCard(message: PremiumMessage) {
    val text = when (message) {
        PremiumMessage.THANKS -> stringResource(Res.string.premium_thanks)
        PremiumMessage.PENDING -> stringResource(Res.string.premium_pending)
        PremiumMessage.FAILED -> stringResource(Res.string.premium_failed)
        PremiumMessage.TOP_UP_ADDED -> stringResource(Res.string.premium_top_up_added, Plans.TOP_UP_VIDEO_HOURS)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(HowYouDoinSpacing.md),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(HowYouDoinSpacing.md))
    }
}

@Composable
private fun PlanCard(state: PremiumState, onAction: (PremiumAction) -> Unit) {
    val isPremium = state.isPremium
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isPremium) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        modifier = Modifier.fillMaxWidth().padding(HowYouDoinSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(HowYouDoinSpacing.md)) {
            Icon(
                Icons.Rounded.WorkspacePremium,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(HowYouDoinSpacing.md))
            Column {
                Text(
                    stringResource(if (isPremium) Res.string.premium_title_active else Res.string.premium_title_free),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(if (isPremium) Res.string.premium_active_body else Res.string.premium_free_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SubscriptionButtons(state, onAction)
    }
}

@Composable
private fun Minutes(state: PremiumState) {
    val minutes = state.minutes
    when {
        minutes != null -> MinutesLeft(minutes)
        state.minutesFailed -> Text(
            stringResource(Res.string.premium_minutes_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md),
        )
        else -> CircularProgressIndicator(Modifier.padding(HowYouDoinSpacing.md).size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun MinutesLeft(minutes: CallMinutes) {
    // Whole days, rounded up: "in 1 day" on the last day
    val untilReset = minutes.resetsAtMs - Clock.System.now().toEpochMilliseconds()
    val days = ((untilReset + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(1)
    Column {
        SettingsRow(
            title = stringResource(Res.string.premium_video_left, duration(minutes.videoLeftMs)),
            subtitle = minutes.topUpMs.takeIf { it > 0 }?.let {
                stringResource(Res.string.premium_video_bought, duration(it))
            },
            icon = Icons.Rounded.Videocam,
        )
        SettingsRow(
            title = stringResource(Res.string.premium_audio_left, duration(minutes.audioLeftMs)),
            subtitle = pluralStringResource(Res.plurals.premium_resets_in, days, days),
            icon = Icons.Rounded.Mic,
        )
        Text(
            stringResource(Res.string.premium_guests_free),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md),
        )
    }
}

@Composable
private fun Benefits() {
    Column(
        verticalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm),
        modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md),
    ) {
        Benefit(stringResource(Res.string.premium_benefit_video, Plans.PREMIUM_VIDEO_HOURS, Plans.FREE_VIDEO_MINUTES))
        Benefit(stringResource(Res.string.premium_benefit_audio, Plans.PREMIUM_AUDIO_HOURS, Plans.FREE_AUDIO_HOURS))
        Benefit(
            stringResource(Res.string.premium_benefit_quality, Plans.PREMIUM_VIDEO_HEIGHT, Plans.FREE_VIDEO_HEIGHT)
        )
        Benefit(stringResource(Res.string.premium_benefit_length, Plans.PREMIUM_CALL_HOURS, Plans.FREE_CALL_MINUTES))
        Benefit(stringResource(Res.string.premium_benefit_accounts, MAX_ACCOUNTS_PER_NETWORK))
    }
}

@Composable
private fun Benefit(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Rounded.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(HowYouDoinSpacing.sm))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SubscriptionButtons(state: PremiumState, onAction: (PremiumAction) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = HowYouDoinSpacing.md, end = HowYouDoinSpacing.md, bottom = HowYouDoinSpacing.md)
    ) {
        val offer = state.subscription
        when {
            state.isPremium -> OutlinedButton(onClick = { onAction(PremiumAction.OnManageClick) }) {
                Text(stringResource(Res.string.premium_manage))
            }
            offer != null -> {
                val trial = offer.trialDays
                Button(
                    onClick = { onAction(PremiumAction.OnSubscribeClick) },
                    enabled = !state.isBuying,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (state.isBuying) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            if (trial != null) {
                                pluralStringResource(Res.plurals.premium_try_free, trial, trial)
                            } else {
                                stringResource(Res.string.premium_subscribe, offer.price)
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Text(
                    stringResource(Res.string.premium_then_price, offer.price),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = HowYouDoinSpacing.xs),
                )
            }
            state.store == PremiumStore.CHECKING ->
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            // Not buyable here: say why, and what to do instead
            else -> {
                val noPlay = state.store == PremiumStore.NO_GOOGLE_PLAY
                Text(
                    stringResource(if (noPlay) Res.string.premium_no_play else Res.string.premium_not_offered),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { onAction(if (noPlay) PremiumAction.OnOpenPlayClick else PremiumAction.OnRestoreClick) },
                    modifier = Modifier.padding(top = HowYouDoinSpacing.sm),
                ) {
                    Text(stringResource(if (noPlay) Res.string.premium_open_play else Res.string.premium_try_again))
                }
            }
        }
    }
}

/** "9 h 30 min", "5 h", "42 min". */
@Composable
private fun duration(ms: Long): String {
    val totalMinutes = (ms / MINUTE_MS).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> stringResource(Res.string.premium_duration_m, minutes)
        minutes == 0 -> stringResource(Res.string.premium_duration_h, hours)
        else -> stringResource(Res.string.premium_duration_hm, hours, minutes)
    }
}

private const val MESSAGE_MS = 5_000L
private const val MINUTE_MS = 60_000L
private const val DAY_MS = 24 * 60 * MINUTE_MS
