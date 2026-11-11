package com.devvalor.howyoudoing.chat.presentation.inbox

import com.devvalor.howyoudoing.core.presentation.util.UiText
import howyoudoin.feature.chat.presentation.generated.resources.keys_error_retry
import howyoudoin.feature.chat.presentation.generated.resources.keys_error_wrong
import howyoudoin.feature.chat.presentation.generated.resources.keys_error_none
import howyoudoin.feature.chat.presentation.generated.resources.keys_error_other
import howyoudoin.feature.chat.presentation.generated.resources.keys_restored
import howyoudoin.feature.chat.presentation.generated.resources.keys_restored_none
import howyoudoin.feature.chat.presentation.generated.resources.keys_ok
import howyoudoin.feature.chat.presentation.generated.resources.keys_open_networks
import howyoudoin.feature.chat.presentation.generated.resources.keys_not_now
import howyoudoin.feature.chat.presentation.generated.resources.keys_dont_show
import howyoudoin.feature.chat.presentation.generated.resources.keys_enter
import howyoudoin.feature.chat.presentation.generated.resources.keys_web_1
import howyoudoin.feature.chat.presentation.generated.resources.keys_web_2
import howyoudoin.feature.chat.presentation.generated.resources.keys_web_3
import howyoudoin.feature.chat.presentation.generated.resources.keys_web_4
import howyoudoin.feature.chat.presentation.generated.resources.keys_phone_1
import howyoudoin.feature.chat.presentation.generated.resources.keys_phone_2
import howyoudoin.feature.chat.presentation.generated.resources.keys_phone_3
import org.jetbrains.compose.resources.pluralStringResource
import howyoudoin.feature.chat.presentation.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import howyoudoin.feature.chat.presentation.generated.resources.conversation_cancel
import howyoudoin.feature.chat.presentation.generated.resources.imageviewer_close
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_element_can_t_show_an
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_element_on_a_computer_web
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_element_x_on_a_phone
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_find_your_recovery_key
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_i_have_my_key
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_matrix_device_verified
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_matrix_recovery_key
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_messages_sent_before_howyoudoin_was
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_recovery_key
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_sign_in_to_matrix_again
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_skip
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_the_recovery_key_element_may
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_unlock_older_encrypted_matrix_messages
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_verify
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_you_got_it_when_you
import howyoudoin.feature.chat.presentation.generated.resources.matrixkeys_your_matrix_login_came_back
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.IconButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.chat.domain.MatrixEncryption
import com.devvalor.howyoudoing.chat.domain.MatrixEncryptionStatus
import com.devvalor.howyoudoing.core.domain.app.HiddenTips
import com.devvalor.howyoudoing.core.domain.app.Tip
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

data class MatrixKeysState(
    val isOffered: Boolean = false,
    /** Encrypted chats need a new Matrix sign-in (keys lost with a restore). */
    val needsSignIn: Boolean = false,
    /** Where to find the recovery key, shown before asking for it. */
    val isGuideOpen: Boolean = false,
    val isDialogOpen: Boolean = false,
    val recoveryKey: String = "",
    val isSubmitting: Boolean = false,
    val error: UiText? = null,
    /** After verifying: how many older message keys came back from the key backup. */
    val restoredKeys: Int? = null
) {
    val canSubmit: Boolean get() = recoveryKey.isNotBlank() && !isSubmitting
}

/** Offers verifying this device with the Matrix recovery key while it isn't verified. */
class MatrixKeysViewModel(
    private val encryption: MatrixEncryption,
    private val hiddenTips: HiddenTips,
) : ViewModel() {

    private val _state = MutableStateFlow(MatrixKeysState())
    val state = _state.asStateFlow()
    private var isDismissed = false

    init {
        combine(encryption.status, hiddenTips.observeHidden(Tip.MATRIX_KEYS)) { status, isHidden -> status to isHidden }
            .onEach { (status, isHidden) ->
                _state.update {
                    it.copy(
                        // "Don't show again" only turns off the offer; a lost login still has to be fixed
                        isOffered = status == MatrixEncryptionStatus.UNVERIFIED && !isDismissed && !isHidden,
                        needsSignIn = status == MatrixEncryptionStatus.NEEDS_SIGN_IN && !isDismissed
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun open() = _state.update { it.copy(isGuideOpen = true) }

    fun closeGuide() = _state.update { it.copy(isGuideOpen = false) }

    /** From the guide (or skipping it) to entering the key. */
    fun enterKey() = _state.update { it.copy(isGuideOpen = false, isDialogOpen = true, recoveryKey = "", error = null) }

    fun notNow() {
        isDismissed = true
        _state.update { it.copy(isOffered = false, needsSignIn = false) }
    }

    /** Never offer it again (Settings → Hidden tips brings it back). */
    fun dontShowAgain() {
        notNow()
        viewModelScope.launch { hiddenTips.hide(Tip.MATRIX_KEYS) }
    }

    fun onKeyChange(key: String) = _state.update { it.copy(recoveryKey = key, error = null) }

    fun dismissDialog() =
        _state.update { if (it.isSubmitting) it else it.copy(isDialogOpen = false, recoveryKey = "", error = null) }

    fun dismissResult() = _state.update { it.copy(restoredKeys = null) }

    fun submit() {
        val key = _state.value.recoveryKey
        if (key.isBlank() || _state.value.isSubmitting) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            encryption.verifyWithRecoveryKey(key)
                .onSuccess { restored ->
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            isDialogOpen = false,
                            recoveryKey = "",
                            restoredKeys = restored
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(isSubmitting = false, error = friendlyError(error.message)) }
                }
        }
    }

    private fun friendlyError(message: String?): UiText = when {
        message == null -> UiText.Resource(Res.string.keys_error_retry)
        "wrong recovery key" in message || "invalid" in message.lowercase() ->
            UiText.Resource(Res.string.keys_error_wrong)
        "no recovery key" in message -> UiText.Resource(Res.string.keys_error_none)
        else -> UiText.Resource(Res.string.keys_error_other, arrayOf(message))
    }
}

@Composable
fun MatrixKeysBannerRoot(onNetworksClick: () -> Unit, viewModel: MatrixKeysViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val restored = state.restoredKeys
    when {
        restored != null -> KeysCard(
            title = stringResource(Res.string.matrixkeys_matrix_device_verified),
            body = if (restored > 0) {
                pluralStringResource(Res.plurals.keys_restored, restored, restored)
            } else {
                stringResource(Res.string.keys_restored_none)
            },
            primary = stringResource(Res.string.keys_ok) to viewModel::dismissResult,
        )

        state.needsSignIn -> KeysCard(
            title = stringResource(Res.string.matrixkeys_sign_in_to_matrix_again),
            body = stringResource(Res.string.matrixkeys_your_matrix_login_came_back),
            primary = stringResource(Res.string.keys_open_networks) to onNetworksClick,
            secondary = stringResource(Res.string.keys_not_now) to viewModel::notNow,
        )

        state.isOffered -> KeysCard(
            title = stringResource(Res.string.matrixkeys_unlock_older_encrypted_matrix_messages),
            body = stringResource(Res.string.matrixkeys_messages_sent_before_howyoudoin_was),
            primary = stringResource(Res.string.keys_enter) to viewModel::open,
            secondary = stringResource(Res.string.keys_not_now) to viewModel::notNow,
            tertiary = stringResource(Res.string.keys_dont_show) to viewModel::dontShowAgain,
        )
    }
    if (state.isGuideOpen) {
        RecoveryKeyGuide(onContinue = viewModel::enterKey, onClose = viewModel::closeGuide)
    }
    if (state.isDialogOpen) {
        RecoveryKeyDialog(
            state = state,
            onKeyChange = viewModel::onKeyChange,
            onSubmit = viewModel::submit,
            onDismiss = viewModel::dismissDialog,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeysCard(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    tertiary: Pair<String, () -> Unit>? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
    ) {
        Column(Modifier.padding(HowYouDoinSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(HowYouDoinSpacing.sm))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.padding(top = HowYouDoinSpacing.xs))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            // "Don't show again" goes on its own line when all three don't fit; the other two stay together
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                tertiary?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
                Row {
                    secondary?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
                    TextButton(onClick = primary.second) { Text(primary.first, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun RecoveryKeyDialog(
    state: MatrixKeysState,
    onKeyChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.matrixkeys_matrix_recovery_key)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.matrixkeys_the_recovery_key_element_may),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.padding(top = HowYouDoinSpacing.sm))
                OutlinedTextField(
                    value = state.recoveryKey,
                    onValueChange = onKeyChange,
                    label = { Text(stringResource(Res.string.matrixkeys_recovery_key)) },
                    enabled = !state.isSubmitting,
                    isError = state.error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    Spacer(Modifier.padding(top = HowYouDoinSpacing.sm))
                    Text(
                        it.asString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = state.canSubmit) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(Res.string.matrixkeys_verify))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isSubmitting
            ) { Text(stringResource(Res.string.conversation_cancel)) }
        },
    )
}

/** Full-screen steps for finding the recovery key in Element, on a computer and on a phone. */
@Composable
private fun RecoveryKeyGuide(onContinue: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.imageviewer_close)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onContinue) { Text(stringResource(Res.string.matrixkeys_skip)) }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = HowYouDoinSpacing.lg)
                ) {
                    Text(
                        stringResource(Res.string.matrixkeys_find_your_recovery_key),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(HowYouDoinSpacing.sm))
                    Text(
                        stringResource(Res.string.matrixkeys_you_got_it_when_you),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    GuideSection(
                        title = stringResource(Res.string.matrixkeys_element_on_a_computer_web),
                        steps = listOf(
                            stringResource(Res.string.keys_web_1),
                            stringResource(Res.string.keys_web_2),
                            stringResource(Res.string.keys_web_3),
                            stringResource(Res.string.keys_web_4),
                        ),
                    )
                    GuideSection(
                        title = stringResource(Res.string.matrixkeys_element_x_on_a_phone),
                        steps = listOf(
                            stringResource(Res.string.keys_phone_1),
                            stringResource(Res.string.keys_phone_2),
                            stringResource(Res.string.keys_phone_3),
                        ),
                    )
                    Spacer(Modifier.height(HowYouDoinSpacing.md))
                    Text(
                        stringResource(Res.string.matrixkeys_element_can_t_show_an),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(HowYouDoinSpacing.lg))
                }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().padding(HowYouDoinSpacing.lg),
                ) { Text(stringResource(Res.string.matrixkeys_i_have_my_key)) }
            }
        }
    }
}

@Composable
private fun GuideSection(title: String, steps: List<String>) {
    Spacer(Modifier.height(HowYouDoinSpacing.lg))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(HowYouDoinSpacing.xs))
    steps.forEachIndexed { index, step ->
        Row(Modifier.padding(vertical = HowYouDoinSpacing.xs)) {
            Text("${index + 1}.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(24.dp))
            Text(step, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
