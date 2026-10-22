package com.devvalor.howyoudoing.app.backup

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.domain.backup.LoginBackup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

enum class BackupOfferStep {
    /** "Back up your logins?" */
    ASK,
    /** How to turn on Google backup (shown when the phone hasn't backed the app up yet). */
    TURN_ON_GOOGLE_BACKUP
}

/** Asks once, after two networks are connected, whether to back up the logins, then helps turn on Google backup. */
class BackupOfferViewModel(private val backup: LoginBackup) : ViewModel() {

    private val _step = MutableStateFlow<BackupOfferStep?>(null)
    val step = _step.asStateFlow()

    init {
        backup.observeShouldOfferBackup()
            .onEach { should -> if (should && _step.value == null) _step.value = BackupOfferStep.ASK }
            .launchIn(viewModelScope)
    }

    fun backUp() {
        viewModelScope.launch {
            backup.answerBackupOffer(enable = true)
            // A backup that already happened shows Google backup works; otherwise explain how to turn it on
            _step.value = if (backup.lastBackupMillis() == null) BackupOfferStep.TURN_ON_GOOGLE_BACKUP else null
        }
    }

    fun skip() {
        viewModelScope.launch {
            backup.answerBackupOffer(enable = false)
            _step.value = null
        }
    }

    fun openBackupSettings() {
        backup.openSystemBackupSettings()
        _step.update { null }
    }

    fun done() = _step.update { null }
}

@Composable
fun BackupOfferRoot(viewModel: BackupOfferViewModel = koinViewModel()) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    when (step) {
        BackupOfferStep.ASK -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Back up your logins?") },
            text = {
                Text(
                    "If you reinstall HowYouDoin on this phone, your logins come back from your Google backup and you " +
                        "won't have to log in to each network again. The backup is end-to-end encrypted with your " +
                        "screen lock; nothing is sent to us.\n\nWhatsApp and Signal always need linking again."
                )
            },
            confirmButton = { TextButton(onClick = viewModel::backUp) { Text("Back up") } },
            dismissButton = { TextButton(onClick = viewModel::skip) { Text("Skip") } },
        )
        BackupOfferStep.TURN_ON_GOOGLE_BACKUP -> AlertDialog(
            onDismissRequest = viewModel::done,
            title = { Text("Turn on Google backup") },
            text = {
                Text(
                    "Your logins are only saved if the phone's backup is on:\n\n" +
                        "1. Open Settings → Google → Backup (on Samsung: Accounts and backup → Back up data).\n" +
                        "2. Turn on \"Backup by Google One\" and choose your Google account.\n" +
                        "3. Keep a screen lock (PIN, pattern or password): backups are encrypted with it.\n\n" +
                        "If it's already on, you're all set. The phone backs up on its own, usually overnight while charging."
                )
            },
            confirmButton = { TextButton(onClick = viewModel::openBackupSettings) { Text("Open backup settings") } },
            dismissButton = { TextButton(onClick = viewModel::done) { Text("Done") } },
        )
        null -> Unit
    }
}
