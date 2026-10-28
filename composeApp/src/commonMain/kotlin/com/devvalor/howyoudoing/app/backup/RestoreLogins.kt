package com.devvalor.howyoudoing.app.backup

import howyoudoin.composeapp.generated.resources.restore_failed
import howyoudoin.composeapp.generated.resources.restore_relink
import howyoudoin.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import howyoudoin.composeapp.generated.resources.restorelogins_continue
import howyoudoin.composeapp.generated.resources.restorelogins_don_t_restore
import howyoudoin.composeapp.generated.resources.restorelogins_not_now
import howyoudoin.composeapp.generated.resources.restorelogins_reconnect
import howyoudoin.composeapp.generated.resources.restorelogins_welcome_back
import howyoudoin.composeapp.generated.resources.restorelogins_your_phone_s_backup_has
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.designsystem.components.NetworkBadge
import com.devvalor.howyoudoing.core.domain.backup.LoginBackup
import com.devvalor.howyoudoing.core.domain.backup.LoginRestoreOffer
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RestoreLoginsState(
    /** Still looking for saved logins; the app waits so it doesn't open behind the offer. */
    val isChecking: Boolean = true,
    val offer: LoginRestoreOffer? = null,
    val selected: Set<ChatNetwork> = emptySet(),
    val isRestoring: Boolean = false,
    /** After restoring: the networks that couldn't be reconnected. */
    val failed: List<ChatNetwork>? = null,
    /** The user answered (or there was nothing to offer). */
    val isDone: Boolean = false
) {
    val isVisible: Boolean get() = !isChecking && !isDone && offer != null
}

/** Offers the logins found in the phone's backup (after a reinstall or on a new phone) before the app opens. */
class RestoreLoginsViewModel(private val backup: LoginBackup) : ViewModel() {

    private val _state = MutableStateFlow(RestoreLoginsState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val offer = backup.restoreOffer()
            _state.update {
                it.copy(
                    isChecking = false,
                    offer = offer,
                    selected = offer?.restorable?.toSet().orEmpty(),
                    isDone = offer == null
                )
            }
        }
    }

    fun toggle(network: ChatNetwork) = _state.update { state ->
        state.copy(selected = if (network in state.selected) state.selected - network else state.selected + network)
    }

    fun restore() {
        if (_state.value.isRestoring) return
        viewModelScope.launch {
            _state.update { it.copy(isRestoring = true) }
            val failed = backup.restore(_state.value.selected)
            _state.update { it.copy(isRestoring = false, failed = failed, isDone = failed.isEmpty()) }
        }
    }

    /** Ask again next time the app starts. */
    fun later() = _state.update { it.copy(isDone = true) }

    fun discard() {
        viewModelScope.launch {
            backup.discard()
            _state.update { it.copy(isDone = true) }
        }
    }

    fun close() = _state.update { it.copy(isDone = true) }
}

@Composable
fun RestoreLoginsScreen(
    state: RestoreLoginsState,
    onToggle: (ChatNetwork) -> Unit,
    onRestore: () -> Unit,
    onLater: () -> Unit,
    onDiscard: () -> Unit,
    onClose: () -> Unit
) {
    val offer = state.offer ?: return
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(HowYouDoinSpacing.lg)
        ) {
            Text(
                stringResource(Res.string.restorelogins_welcome_back),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(HowYouDoinSpacing.sm))
            Text(
                stringResource(Res.string.restorelogins_your_phone_s_backup_has),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(HowYouDoinSpacing.lg))

            val failed = state.failed
            if (failed != null) {
                Text(
                    stringResource(Res.string.restore_failed, failed.joinToString { it.displayName }),
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(HowYouDoinSpacing.lg))
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(Res.string.restorelogins_continue)) }
                return@Column
            }

            offer.restorable.forEach { network ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = network in state.selected,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(network) })
                        .padding(vertical = HowYouDoinSpacing.xs)
                ) {
                    NetworkBadge(network, size = 40.dp, borderColor = Color.Transparent)
                    Spacer(Modifier.width(HowYouDoinSpacing.md))
                    Text(
                        network.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Checkbox(checked = network in state.selected, onCheckedChange = null)
                }
            }
            if (offer.toRelink.isNotEmpty()) {
                Spacer(Modifier.height(HowYouDoinSpacing.md))
                Text(
                    stringResource(Res.string.restore_relink, offer.toRelink.joinToString(", ") { it.displayName }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(HowYouDoinSpacing.lg))
            if (state.isRestoring) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Button(
                    onClick = onRestore,
                    enabled = state.selected.isNotEmpty() || offer.includesAppSession,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(Res.string.restorelogins_reconnect)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDiscard) { Text(stringResource(Res.string.restorelogins_don_t_restore)) }
                    TextButton(onClick = onLater) { Text(stringResource(Res.string.restorelogins_not_now)) }
                }
            }
        }
    }
}
