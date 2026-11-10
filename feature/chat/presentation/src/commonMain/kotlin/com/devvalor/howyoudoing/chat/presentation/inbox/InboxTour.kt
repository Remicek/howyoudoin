package com.devvalor.howyoudoing.chat.presentation.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.tour_done
import howyoudoin.feature.chat.presentation.generated.resources.tour_filters_text
import howyoudoin.feature.chat.presentation.generated.resources.tour_filters_title
import howyoudoin.feature.chat.presentation.generated.resources.tour_networks_text
import howyoudoin.feature.chat.presentation.generated.resources.tour_networks_title
import howyoudoin.feature.chat.presentation.generated.resources.tour_new_text
import howyoudoin.feature.chat.presentation.generated.resources.tour_new_title
import howyoudoin.feature.chat.presentation.generated.resources.tour_next
import howyoudoin.feature.chat.presentation.generated.resources.tour_search_text
import howyoudoin.feature.chat.presentation.generated.resources.tour_search_title
import howyoudoin.feature.chat.presentation.generated.resources.tour_skip
import howyoudoin.feature.chat.presentation.generated.resources.tour_step
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** What the inbox tour points at, in order. */
enum class TourStop(val title: StringResource, val text: StringResource) {
    NETWORKS(Res.string.tour_networks_title, Res.string.tour_networks_text),
    FILTERS(Res.string.tour_filters_title, Res.string.tour_filters_text),
    NEW_MESSAGE(Res.string.tour_new_title, Res.string.tour_new_text),
    SEARCH(Res.string.tour_search_title, Res.string.tour_search_text),
}

/** Where each of the tour's stops is on screen, reported by [tourTarget]. */
@Stable
class TourTargets {
    internal val bounds = mutableStateMapOf<TourStop, Rect>()
}

/** Marks this element as [stop] of the tour, so the tour can light it up. */
fun Modifier.tourTarget(targets: TourTargets, stop: TourStop): Modifier =
    onGloballyPositioned { targets.bounds[stop] = it.boundsInRoot() }

/**
 * A walk through the inbox for a new user: the screen dims except for one control at a time, with a card saying what
 * it's for. "Skip tour" ends it at any step; the controls underneath can't be tapped until it's over.
 */
@Composable
fun InboxTour(targets: TourTargets, onDone: () -> Unit) {
    val stops = TourStop.entries
    var index by rememberSaveable { mutableIntStateOf(0) }
    val stop = stops[index]
    // Nothing until the stop has been laid out (the first frame)
    val target = targets.bounds[stop] ?: return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val scrim = Color.Black.copy(alpha = 0.72f)
    val isLast = index == stops.lastIndex

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            // Swallows taps beside the card, so the tour is answered first. The card is a sibling on top, not a child:
            // a tap whose events a parent consumes gets cancelled
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawBehind {
                val hole = target.translate(-origin).inflate(8.dp.toPx())
                drawRect(scrim)
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = hole.topLeft,
                    size = hole.size,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    blendMode = BlendMode.Clear,
                )
            }
    )
    Layout(
        content = {
            TourCard(
                stop = stop,
                step = index + 1,
                count = stops.size,
                isLast = isLast,
                onSkip = onDone,
                onNext = { if (isLast) onDone() else index++ },
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val margin = HowYouDoinSpacing.md.roundToPx()
        val card = measurables.single().measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = constraints.maxWidth - 2 * margin)
        )
        val hole = target.translate(-origin)
        // Under the lit-up control when it's in the top half of the screen, above it otherwise
        val y = if (hole.center.y < constraints.maxHeight / 2f) {
            hole.bottom.toInt() + margin
        } else {
            hole.top.toInt() - margin - card.height
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            card.place(margin, y.coerceIn(0, (constraints.maxHeight - card.height).coerceAtLeast(0)))
        }
    }
}

@Composable
private fun TourCard(
    stop: TourStop,
    step: Int,
    count: Int,
    isLast: Boolean,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(HowYouDoinSpacing.md)) {
            Text(
                stringResource(Res.string.tour_step, step, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(top = HowYouDoinSpacing.xs))
            Text(
                stringResource(stop.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.padding(top = HowYouDoinSpacing.xs))
            Text(stringResource(stop.text), style = MaterialTheme.typography.bodyMedium)
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = HowYouDoinSpacing.sm),
            ) {
                if (!isLast) TextButton(onClick = onSkip) { Text(stringResource(Res.string.tour_skip)) }
                Spacer(Modifier.padding(start = HowYouDoinSpacing.sm))
                Button(onClick = onNext) {
                    Text(stringResource(if (isLast) Res.string.tour_done else Res.string.tour_next))
                }
            }
        }
    }
}
