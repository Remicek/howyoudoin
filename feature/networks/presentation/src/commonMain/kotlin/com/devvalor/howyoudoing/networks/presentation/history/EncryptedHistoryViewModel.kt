package com.devvalor.howyoudoing.networks.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.networks.domain.BridgeRepository
import com.devvalor.howyoudoing.networks.domain.HistoryRestoreError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Offers restoring Messenger's end-to-end encrypted history only when the inbox actually has encrypted Messenger
 * chats and the account has an unrestored PIN backup.
 */
class EncryptedHistoryViewModel(
    private val bridgeRepository: BridgeRepository
) : ViewModel() {

    private val _state = MutableStateFlow(EncryptedHistoryState())
    val state = _state.asStateFlow()

    private var checkJob: Job? = null
    private var isDismissed = false

    fun onAction(action: EncryptedHistoryAction) {
        when (action) {
            is EncryptedHistoryAction.OnEncryptedNetworksChange -> onEncryptedNetworksChange(action.networks)
            EncryptedHistoryAction.OnEnterPinClick -> _state.update { it.copy(isDialogOpen = true, pin = "", error = null) }
            EncryptedHistoryAction.OnNotNowClick -> {
                isDismissed = true
                _state.update { it.copy(isOffered = false) }
            }
            is EncryptedHistoryAction.OnPinChange -> _state.update {
                it.copy(pin = action.pin.filter(Char::isDigit).take(EncryptedHistoryState.PIN_LENGTH), error = null)
            }
            EncryptedHistoryAction.OnRestoreClick -> restore()
            EncryptedHistoryAction.OnDismissDialog -> _state.update {
                if (it.isSubmitting) it else it.copy(isDialogOpen = false, pin = "", error = null)
            }
            EncryptedHistoryAction.OnDismissRestoring -> _state.update { it.copy(isRestoring = false) }
        }
    }

    private fun onEncryptedNetworksChange(networks: Set<ChatNetwork>) {
        if (ChatNetwork.MESSENGER !in networks) {
            checkJob?.cancel()
            checkJob = null
            _state.update { it.copy(isOffered = false) }
            return
        }
        if (checkJob != null || isDismissed) return
        checkJob = viewModelScope.launch {
            // The bridge needs a moment after (re)connecting before Messenger answers, so retry a few times
            repeat(CHECK_ATTEMPTS) { attempt ->
                when (val result = bridgeRepository.getEncryptedHistoryStatus(ChatNetwork.MESSENGER)) {
                    is Result.Success -> {
                        _state.update { it.copy(isOffered = result.data.canRestore && !isDismissed) }
                        return@launch
                    }
                    is Result.Failure -> if (attempt < CHECK_ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
                }
            }
        }
    }

    private fun restore() {
        val pin = state.value.pin
        if (!state.value.canSubmit) return
        _state.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            when (val result = bridgeRepository.restoreEncryptedHistory(ChatNetwork.MESSENGER, pin)) {
                is Result.Success -> _state.update {
                    it.copy(isSubmitting = false, isDialogOpen = false, pin = "", isOffered = false, isRestoring = true)
                }
                is Result.Failure -> _state.update { it.withError(result.error) }
            }
        }
    }

    private fun EncryptedHistoryState.withError(error: HistoryRestoreError): EncryptedHistoryState {
        val cleared = copy(isSubmitting = false, pin = "")
        return when (error) {
            is HistoryRestoreError.WrongPin -> cleared.copy(
                error = "That PIN didn't work. ${error.attemptsRemaining} " +
                    (if (error.attemptsRemaining == 1) "attempt" else "attempts") + " left before Messenger locks the backup."
            )
            HistoryRestoreError.NoAttemptsLeft -> cleared.copy(
                error = "No attempts left. Messenger has locked your history backup.",
                isOffered = false
            )
            HistoryRestoreError.NoBackup -> cleared.copy(isDialogOpen = false, isOffered = false)
            HistoryRestoreError.SessionExpired -> cleared.copy(
                error = "Your Messenger session expired. Reconnect Messenger under Networks, then try again."
            )
            is HistoryRestoreError.Remote -> cleared.copy(error = "Couldn't restore history right now. Please try again.")
        }
    }

    private companion object {
        const val CHECK_ATTEMPTS = 4
        const val RETRY_DELAY_MILLIS = 15_000L
    }
}
