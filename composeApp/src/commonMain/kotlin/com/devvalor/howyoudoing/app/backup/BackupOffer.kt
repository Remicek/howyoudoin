package com.devvalor.howyoudoing.app.backup

import howyoudoin.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import howyoudoin.composeapp.generated.resources.backupoffer_back_up
import howyoudoin.composeapp.generated.resources.backupoffer_back_up_your_logins
import howyoudoin.composeapp.generated.resources.backupoffer_done
import howyoudoin.composeapp.generated.resources.backupoffer_if_you_reinstall_howyoudoin_on
import howyoudoin.composeapp.generated.resources.backupoffer_open_backup_settings
import howyoudoin.composeapp.generated.resources.backupoffer_skip
import howyoudoin.composeapp.generated.resources.backupoffer_turn_on_google_backup
import howyoudoin.composeapp.generated.resources.backupoffer_your_logins_are_only_saved
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
            title = { Text(stringResource(Res.string.backupoffer_back_up_your_logins)) },
            text = {
                Text(
                    stringResource(Res.string.backupoffer_if_you_reinstall_howyoudoin_on)
                )
            },
            confirmButton = { TextButton(onClick = viewModel::backUp) { Text(stringResource(Res.string.backupoffer_back_up)) } },
            dismissButton = { TextButton(onClick = viewModel::skip) { Text(stringResource(Res.string.backupoffer_skip)) } },
        )
        BackupOfferStep.TURN_ON_GOOGLE_BACKUP -> AlertDialog(
            onDismissRequest = viewModel::done,
            title = { Text(stringResource(Res.string.backupoffer_turn_on_google_backup)) },
            text = {
                Text(
                    stringResource(Res.string.backupoffer_your_logins_are_only_saved)
                )
            },
            confirmButton = { TextButton(onClick = viewModel::openBackupSettings) { Text(stringResource(Res.string.backupoffer_open_backup_settings)) } },
            dismissButton = { TextButton(onClick = viewModel::done) { Text(stringResource(Res.string.backupoffer_done)) } },
        )
        null -> Unit
    }
}
