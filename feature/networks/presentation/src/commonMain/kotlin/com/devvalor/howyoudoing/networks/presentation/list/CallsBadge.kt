package com.devvalor.howyoudoing.networks.presentation.list

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import howyoudoin.feature.networks.presentation.generated.resources.Res
import howyoudoin.feature.networks.presentation.generated.resources.networks_calls_info
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * A microphone and a camera that wiggle now and then next to Matrix: calls only work through a Matrix account for
 * now. Tapping (or long-pressing) it says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsBadge(modifier: Modifier = Modifier) {
    val info = stringResource(Res.string.networks_calls_info)
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(info, modifier = Modifier.widthIn(max = 260.dp)) } },
        state = tooltip,
        modifier = modifier,
    ) {
        val wiggle = rememberInfiniteTransition(label = "calls")
        val micAngle by wiggle.animateFloat(0f, 0f, wiggleSpec(delayMillis = 0), label = "mic")
        val cameraAngle by wiggle.animateFloat(0f, 0f, wiggleSpec(delayMillis = CAMERA_DELAY_MILLIS), label = "camera")
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
                .clickable(role = Role.Button) { scope.launch { tooltip.show() } }
                .semantics(mergeDescendants = true) { contentDescription = info }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            WigglingIcon(Icons.Rounded.Mic, micAngle)
            WigglingIcon(Icons.Rounded.Videocam, cameraAngle)
        }
    }
}

@Composable
private fun WigglingIcon(icon: ImageVector, angle: Float) {
    Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = angle },
    )
}

/** Still for a while, then a short shake that settles, over and over. */
private fun wiggleSpec(delayMillis: Int): InfiniteRepeatableSpec<Float> = infiniteRepeatable(
    animation = keyframes {
        durationMillis = CYCLE_MILLIS
        val start = REST_MILLIS + delayMillis
        0f at start
        SWINGS.forEachIndexed { step, swing -> SHAKE_DEGREES * swing at start + (step + 1) * STEP_MILLIS }
    },
    repeatMode = RepeatMode.Restart,
)

private const val CYCLE_MILLIS = 2_800
private const val REST_MILLIS = 1_500
private const val STEP_MILLIS = 90
private const val CAMERA_DELAY_MILLIS = 120
private const val SHAKE_DEGREES = 14f

/** Each swing of the shake, as a share of [SHAKE_DEGREES]: left, right, smaller, settling. */
private val SWINGS = listOf(1f, -1f, 0.66f, -0.33f, 0f)
