package com.devvalor.howyoudoing.chat.presentation.inbox

import androidx.compose.foundation.layout.Arrangement
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
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val error: String? = null,
    /** After verifying: how many older message keys came back from the key backup. */
    val restoredKeys: Int? = null
) {
    val canSubmit: Boolean get() = recoveryKey.isNotBlank() && !isSubmitting
}

/** Offers verifying this device with the Matrix recovery key while it isn't verified. */
class MatrixKeysViewModel(private val encryption: MatrixEncryption) : ViewModel() {

    private val _state = MutableStateFlow(MatrixKeysState())
    val state = _state.asStateFlow()
    private var isDismissed = false

    init {
        encryption.status
            .onEach { status ->
                _state.update {
                    it.copy(
                        isOffered = status == MatrixEncryptionStatus.UNVERIFIED && !isDismissed,
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

    fun onKeyChange(key: String) = _state.update { it.copy(recoveryKey = key, error = null) }

    fun dismissDialog() = _state.update { if (it.isSubmitting) it else it.copy(isDialogOpen = false, recoveryKey = "", error = null) }

    fun dismissResult() = _state.update { it.copy(restoredKeys = null) }

    fun submit() {
        val key = _state.value.recoveryKey
        if (key.isBlank() || _state.value.isSubmitting) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            encryption.verifyWithRecoveryKey(key)
                .onSuccess { restored ->
                    _state.update { it.copy(isSubmitting = false, isDialogOpen = false, recoveryKey = "", restoredKeys = restored) }
                }
                .onFailure { error ->
                    _state.update { it.copy(isSubmitting = false, error = friendlyError(error.message)) }
                }
        }
    }

    private fun friendlyError(message: String?): String = when {
        message == null -> "Couldn't verify. Try again."
        "wrong recovery key" in message || "invalid" in message.lowercase() ->
            "That isn't this account's recovery key. Check it in Element (Settings → Encryption)."
        "no recovery key" in message -> "This Matrix account has no recovery key yet. Set one up in Element first."
        else -> "Couldn't verify: $message"
    }
}

@Composable
fun MatrixKeysBannerRoot(onNetworksClick: () -> Unit, viewModel: MatrixKeysViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val restored = state.restoredKeys
    when {
        restored != null -> KeysCard(
            title = "Matrix device verified",
            body = if (restored > 0) {
                "Keys for $restored older encrypted conversations were restored; their messages unlock as they load."
            } else {
                "New encrypted messages can be read. There was no key backup with older messages to restore."
            },
            primary = "OK" to viewModel::dismissResult,
        )
        state.needsSignIn -> KeysCard(
            title = "Sign in to Matrix again",
            body = "Your Matrix login came back from a backup, but encryption keys never leave a phone, so " +
                "encrypted chats can't be read here. In Networks, disconnect Matrix and sign in again.",
            primary = "Open Networks" to onNetworksClick,
            secondary = "Not now" to viewModel::notNow,
        )
        state.isOffered -> KeysCard(
            title = "Unlock older encrypted Matrix messages",
            body = "Messages sent before HowYouDoin was connected stay locked, and some apps won't share keys with an " +
                "unverified device. Verify it with your Matrix recovery key.",
            primary = "Enter recovery key" to viewModel::open,
            secondary = "Not now" to viewModel::notNow,
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

@Composable
private fun KeysCard(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
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
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                secondary?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
                TextButton(onClick = primary.second) { Text(primary.first, fontWeight = FontWeight.SemiBold) }
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
        title = { Text("Matrix recovery key") },
        text = {
            Column {
                Text(
                    "The recovery key (Element may call it Security Key) you saved when setting up encryption, " +
                        "48 letters and numbers in groups of four. Not your password.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.padding(top = HowYouDoinSpacing.sm))
                OutlinedTextField(
                    value = state.recoveryKey,
                    onValueChange = onKeyChange,
                    label = { Text("Recovery key") },
                    enabled = !state.isSubmitting,
                    isError = state.error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    Spacer(Modifier.padding(top = HowYouDoinSpacing.sm))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = state.canSubmit) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Verify")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.isSubmitting) { Text("Cancel") } },
    )
}

/** Full-screen steps for finding the recovery key in Element, on a computer and on a phone. */
@Composable
private fun RecoveryKeyGuide(onContinue: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onContinue) { Text("Skip") }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = HowYouDoinSpacing.lg)
                ) {
                    Text("Find your recovery key", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(HowYouDoinSpacing.sm))
                    Text(
                        "You got it when you turned on encryption backup in Element: 48 letters and numbers in groups " +
                            "of four (EsTc 9Z3k …). Element may call it Security Key. Check your password manager, " +
                            "notes or a saved file first.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    GuideSection(
                        title = "Element on a computer (web or desktop)",
                        steps = listOf(
                            "Click your profile picture in the top left, then All settings.",
                            "Open Encryption.",
                            "Under Recovery, choose Change recovery key (or Set up recovery if there's none yet).",
                            "Save the new key somewhere safe, then type it here.",
                        ),
                    )
                    GuideSection(
                        title = "Element X on a phone",
                        steps = listOf(
                            "Tap your profile picture, then Encryption.",
                            "Tap Recovery, then Change recovery key (or Set up recovery).",
                            "Save the key, then type it here.",
                        ),
                    )
                    Spacer(Modifier.height(HowYouDoinSpacing.md))
                    Text(
                        "Element can't show an existing key again; changing it makes a new one and the old one stops " +
                            "working. Do it from a session that's already verified so your old messages stay readable.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(HowYouDoinSpacing.lg))
                }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().padding(HowYouDoinSpacing.lg),
                ) { Text("I have my key") }
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
