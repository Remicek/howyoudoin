package com.devvalor.howyoudoing.app.background

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.domain.ondevice.BackgroundDelivery
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import howyoudoin.composeapp.generated.resources.Res
import howyoudoin.composeapp.generated.resources.background_allow
import howyoudoin.composeapp.generated.resources.background_not_now
import howyoudoin.composeapp.generated.resources.background_text
import howyoudoin.composeapp.generated.resources.background_title
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Once a network is connected, asks to let the app run in the background (if the phone would otherwise pause it), so
 * messages arrive while the phone isn't in use. Asks again if that's taken away later, unless the user said not now.
 */
class BackgroundOfferViewModel(
    private val delivery: BackgroundDelivery,
    monitor: ConnectionMonitor,
) : ViewModel() {

    // Not when staying connected is off in Settings: nothing is meant to run in the background then
    val isVisible = combine(
        delivery.isAllowed,
        delivery.isDeclined,
        delivery.keepsConnected,
        monitor.linkedNetworks,
    ) { allowed, declined, keep, linked ->
        !allowed && !declined && keep && linked.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun refresh() = delivery.refresh()
    fun allow() = delivery.requestAllowed()
    fun notNow() = delivery.decline()
}

@Composable
fun BackgroundOfferRoot(viewModel: BackgroundOfferViewModel = koinViewModel()) {
    // Back from the system's prompt (or settings), whatever was chosen there
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val isVisible by viewModel.isVisible.collectAsStateWithLifecycle()
    if (!isVisible) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.background_title)) },
        text = { Text(stringResource(Res.string.background_text)) },
        confirmButton = {
            TextButton(onClick = viewModel::allow) { Text(stringResource(Res.string.background_allow)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::notNow) { Text(stringResource(Res.string.background_not_now)) }
        },
    )
}
