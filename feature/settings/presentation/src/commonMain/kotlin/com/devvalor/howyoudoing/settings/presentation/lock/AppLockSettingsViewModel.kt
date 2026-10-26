package com.devvalor.howyoudoing.settings.presentation.lock

import com.devvalor.howyoudoing.core.presentation.util.UiText
import org.jetbrains.compose.resources.getString
import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.bio_use_title
import howyoudoin.feature.settings.presentation.generated.resources.bio_confirm
import howyoudoin.feature.settings.presentation.generated.resources.pin_wrong
import howyoudoin.feature.settings.presentation.generated.resources.pin_too_many
import howyoudoin.feature.settings.presentation.generated.resources.pin_again
import howyoudoin.feature.settings.presentation.generated.resources.bio_instead_of_pin
import howyoudoin.feature.settings.presentation.generated.resources.pin_mismatch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.settings.domain.AppLockRepository
import com.devvalor.howyoudoing.settings.domain.AppLockSettings
import com.devvalor.howyoudoing.settings.domain.BiometricAuthenticator
import com.devvalor.howyoudoing.settings.domain.LockTimeout
import com.devvalor.howyoudoing.settings.domain.PinCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the PIN pad is in setting up, changing or turning off the lock. */
sealed interface PinStep {
    /** Confirm the current PIN before [then]. */
    data class Current(val then: PinPurpose) : PinStep
    data object New : PinStep
    data class Confirm(val first: String) : PinStep
}

enum class PinPurpose { CHANGE, TURN_OFF }

data class AppLockSettingsState(
    val settings: AppLockSettings = AppLockSettings(),
    val isBiometricAvailable: Boolean = false,
    val pinStep: PinStep? = null,
    val pinLength: Int = 0,
    val pinMessage: UiText? = null,
    val pinError: Boolean = false,
    val isTimeoutDialogOpen: Boolean = false,
    /** Setup started from the sign-in prompt is over (PIN set, or cancelled); the screen closes. */
    val isSetupFinished: Boolean = false,
)

sealed interface AppLockSettingsAction {
    data object OnTurnOnClick : AppLockSettingsAction
    /** Opened from the prompt after signing in: go straight to choosing a PIN, then offer the fingerprint. */
    data object OnStartSetup : AppLockSettingsAction
    data object OnTurnOffClick : AppLockSettingsAction
    data object OnChangePinClick : AppLockSettingsAction
    data class OnBiometricChange(val enabled: Boolean) : AppLockSettingsAction
    data class OnHideNotificationsChange(val hides: Boolean) : AppLockSettingsAction
    data object OnLockAfterClick : AppLockSettingsAction
    data class OnLockAfterSelect(val timeout: LockTimeout) : AppLockSettingsAction
    data object OnDismissDialog : AppLockSettingsAction
    data class OnDigit(val digit: Int) : AppLockSettingsAction
    data object OnDelete : AppLockSettingsAction
    data object OnSubmit : AppLockSettingsAction
    data object OnCancelPin : AppLockSettingsAction
    data object OnBackClick : AppLockSettingsAction
}

class AppLockSettingsViewModel(
    private val repository: AppLockRepository,
    private val biometrics: BiometricAuthenticator,
) : ViewModel() {

    private val _state = MutableStateFlow(AppLockSettingsState(isBiometricAvailable = biometrics.isAvailable()))
    val state = _state.asStateFlow()

    private var pin = ""
    private var isSetup = false

    init {
        viewModelScope.launch {
            repository.observeSettings().collect { settings -> _state.update { it.copy(settings = settings) } }
        }
    }

    fun onAction(action: AppLockSettingsAction) {
        when (action) {
            AppLockSettingsAction.OnTurnOnClick -> startPin(PinStep.New)
            AppLockSettingsAction.OnStartSetup -> if (!isSetup) {
                isSetup = true
                startPin(PinStep.New)
            }
            AppLockSettingsAction.OnTurnOffClick -> startPin(PinStep.Current(PinPurpose.TURN_OFF))
            AppLockSettingsAction.OnChangePinClick -> startPin(PinStep.Current(PinPurpose.CHANGE))
            is AppLockSettingsAction.OnBiometricChange -> viewModelScope.launch {
                // Make sure the fingerprint works before relying on it
                if (!action.enabled || biometrics.authenticate(getString(Res.string.bio_use_title), getString(Res.string.bio_confirm))) {
                    repository.setBiometricEnabled(action.enabled)
                }
            }
            is AppLockSettingsAction.OnHideNotificationsChange -> viewModelScope.launch {
                repository.setHidesNotificationContent(action.hides)
            }
            AppLockSettingsAction.OnLockAfterClick -> _state.update { it.copy(isTimeoutDialogOpen = true) }
            is AppLockSettingsAction.OnLockAfterSelect -> {
                _state.update { it.copy(isTimeoutDialogOpen = false) }
                viewModelScope.launch { repository.setLockAfter(action.timeout) }
            }
            AppLockSettingsAction.OnDismissDialog -> _state.update { it.copy(isTimeoutDialogOpen = false) }
            is AppLockSettingsAction.OnDigit -> if (pin.length < PIN_MAX_LENGTH) {
                pin += action.digit
                _state.update { it.copy(pinLength = pin.length, pinMessage = null, pinError = false) }
            }
            AppLockSettingsAction.OnDelete -> if (pin.isNotEmpty()) {
                pin = pin.dropLast(1)
                _state.update { it.copy(pinLength = pin.length) }
            }
            AppLockSettingsAction.OnSubmit -> submitPin()
            AppLockSettingsAction.OnCancelPin -> {
                closePin()
                if (isSetup) _state.update { it.copy(isSetupFinished = true) }
            }
            AppLockSettingsAction.OnBackClick -> Unit
        }
    }

    private fun closePin() {
        pin = ""
        _state.update { it.copy(pinStep = null, pinLength = 0, pinMessage = null, pinError = false) }
    }

    private fun startPin(step: PinStep, message: UiText? = null, isError: Boolean = false) {
        pin = ""
        _state.update { it.copy(pinStep = step, pinLength = 0, pinMessage = message, pinError = isError) }
    }

    private fun submitPin() {
        if (pin.length < PIN_MIN_LENGTH) return
        val entered = pin
        when (val step = _state.value.pinStep) {
            is PinStep.Current -> viewModelScope.launch {
                when (val result = repository.checkPin(entered)) {
                    PinCheck.Correct -> when (step.then) {
                        PinPurpose.CHANGE -> startPin(PinStep.New)
                        PinPurpose.TURN_OFF -> {
                            repository.disable()
                            closePin()
                        }
                    }
                    is PinCheck.Wrong -> startPin(step, UiText.Resource(Res.string.pin_wrong), isError = true)
                    is PinCheck.TooManyAttempts -> startPin(step, UiText.Resource(Res.string.pin_too_many, arrayOf(result.seconds)), isError = true)
                }
            }
            PinStep.New -> startPin(PinStep.Confirm(entered), UiText.Resource(Res.string.pin_again))
            is PinStep.Confirm -> if (entered == step.first) {
                viewModelScope.launch {
                    repository.setPin(entered)
                    closePin()
                    if (isSetup) {
                        if (biometrics.isAvailable() && biometrics.authenticate(getString(Res.string.bio_use_title), getString(Res.string.bio_instead_of_pin))) {
                            repository.setBiometricEnabled(true)
                        }
                        _state.update { it.copy(isSetupFinished = true) }
                    }
                }
            } else {
                startPin(PinStep.New, UiText.Resource(Res.string.pin_mismatch), isError = true)
            }
            null -> Unit
        }
    }
}
