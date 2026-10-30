package com.devvalor.howyoudoing.settings.presentation.telegram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.domain.ondevice.TelegramApiKeys
import com.devvalor.howyoudoing.settings.presentation.components.SettingsScaffold
import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.settings_telegram_keys
import howyoudoin.feature.settings.presentation.generated.resources.telegram_keys_hash
import howyoudoin.feature.settings.presentation.generated.resources.telegram_keys_id
import howyoudoin.feature.settings.presentation.generated.resources.telegram_keys_intro
import howyoudoin.feature.settings.presentation.generated.resources.telegram_keys_invalid
import howyoudoin.feature.settings.presentation.generated.resources.telegram_keys_save
import howyoudoin.feature.settings.presentation.generated.resources.telegram_keys_saved
import howyoudoin.feature.settings.presentation.generated.resources.telegram_keys_use_builtin
import howyoudoin.feature.settings.presentation.generated.resources.telegram_keys_using_builtin
import howyoudoin.feature.settings.presentation.generated.resources.telegram_keys_using_own
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

data class TelegramKeysState(
    val apiId: String = "",
    val apiHash: String = "",
    /** The user's own keys are saved (and used from the next start). */
    val usesOwn: Boolean = false,
    val isInvalid: Boolean = false,
    /** Saved or cleared just now: the app has to be restarted. */
    val needsRestart: Boolean = false,
)

sealed interface TelegramKeysAction {
    data class OnApiIdChange(val value: String) : TelegramKeysAction
    data class OnApiHashChange(val value: String) : TelegramKeysAction
    data object OnSaveClick : TelegramKeysAction
    data object OnUseBuiltInClick : TelegramKeysAction
    data object OnBackClick : TelegramKeysAction
}

class TelegramKeysViewModel(private val keys: TelegramApiKeys) : ViewModel() {
    private val form = MutableStateFlow(TelegramKeysState())

    val state = combine(form, keys.observeOwn()) { f, own -> f.copy(usesOwn = own != null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), TelegramKeysState())

    fun onAction(action: TelegramKeysAction) {
        when (action) {
            is TelegramKeysAction.OnApiIdChange -> form.update { it.copy(apiId = action.value, isInvalid = false) }
            is TelegramKeysAction.OnApiHashChange -> form.update { it.copy(apiHash = action.value, isInvalid = false) }
            TelegramKeysAction.OnSaveClick -> {
                val parsed = TelegramApiKeys.parse(form.value.apiId, form.value.apiHash)
                if (parsed == null) {
                    form.update { it.copy(isInvalid = true) }
                } else {
                    viewModelScope.launch { keys.setOwn(parsed) }
                    // The hash isn't kept on screen once saved
                    form.update { TelegramKeysState(needsRestart = true) }
                }
            }
            TelegramKeysAction.OnUseBuiltInClick -> {
                viewModelScope.launch { keys.setOwn(null) }
                form.update { TelegramKeysState(needsRestart = true) }
            }
            TelegramKeysAction.OnBackClick -> Unit
        }
    }
}

@Composable
fun TelegramKeysRoot(onBack: () -> Unit, viewModel: TelegramKeysViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TelegramKeysScreen(state) { action ->
        if (action == TelegramKeysAction.OnBackClick) onBack() else viewModel.onAction(action)
    }
}

@Composable
fun TelegramKeysScreen(state: TelegramKeysState, onAction: (TelegramKeysAction) -> Unit) {
    val padding = Modifier.fillMaxWidth().padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm)
    SettingsScaffold(
        title = stringResource(Res.string.settings_telegram_keys),
        onBack = { onAction(TelegramKeysAction.OnBackClick) }
    ) {
        item {
            Text(
                stringResource(Res.string.telegram_keys_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = padding,
            )
        }
        item {
            Text(
                stringResource(
                    if (state.usesOwn) Res.string.telegram_keys_using_own else Res.string.telegram_keys_using_builtin
                ),
                style = MaterialTheme.typography.titleSmall,
                modifier = padding,
            )
        }
        item {
            OutlinedTextField(
                value = state.apiId,
                onValueChange = { onAction(TelegramKeysAction.OnApiIdChange(it)) },
                label = { Text(stringResource(Res.string.telegram_keys_id)) },
                singleLine = true,
                isError = state.isInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = padding,
            )
        }
        item {
            OutlinedTextField(
                value = state.apiHash,
                onValueChange = { onAction(TelegramKeysAction.OnApiHashChange(it)) },
                label = { Text(stringResource(Res.string.telegram_keys_hash)) },
                singleLine = true,
                isError = state.isInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                supportingText = if (state.isInvalid) {
                    { Text(stringResource(Res.string.telegram_keys_invalid)) }
                } else null,
                modifier = padding,
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm),
                modifier = Modifier.padding(HowYouDoinSpacing.md),
            ) {
                FilledTonalButton(onClick = { onAction(TelegramKeysAction.OnSaveClick) }) {
                    Text(stringResource(Res.string.telegram_keys_save))
                }
                if (state.usesOwn) {
                    OutlinedButton(onClick = { onAction(TelegramKeysAction.OnUseBuiltInClick) }) {
                        Text(stringResource(Res.string.telegram_keys_use_builtin))
                    }
                }
            }
        }
        if (state.needsRestart) {
            item {
                Text(
                    stringResource(Res.string.telegram_keys_saved),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = padding,
                )
            }
        }
    }
}
