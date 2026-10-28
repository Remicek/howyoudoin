@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.devvalor.howyoudoing.settings.presentation.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.presentation.util.UiText
import com.devvalor.howyoudoing.settings.domain.AppLockRepository
import com.devvalor.howyoudoing.settings.domain.AppLockSettings
import com.devvalor.howyoudoing.settings.domain.BiometricAuthenticator
import com.devvalor.howyoudoing.settings.domain.PinCheck
import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.bio_unlock_sub
import howyoudoin.feature.settings.presentation.generated.resources.bio_unlock_title
import howyoudoin.feature.settings.presentation.generated.resources.pin_too_many
import howyoudoin.feature.settings.presentation.generated.resources.pin_wrong
import howyoudoin.feature.settings.presentation.generated.resources.pin_wrong_tries_left
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class AppLockState(
    /** Settings loaded; until then nothing is shown, so the inbox can't flash before the lock. */
    val isReady: Boolean = false,
    val isLocked: Boolean = false,
    val pinLength: Int = 0,
    val message: UiText? = null,
    val isError: Boolean = false,
    /** Seconds until another PIN may be tried. */
    val waitSeconds: Long = 0,
    val canUseBiometric: Boolean = false,
    val isChecking: Boolean = false,
)

sealed interface AppLockAction {
    data class OnDigit(val digit: Int) : AppLockAction
    data object OnDelete : AppLockAction
    data object OnSubmit : AppLockAction
    data object OnBiometricClick : AppLockAction

    /** The app went to the background (screen off, another app). */
    data object OnAppHidden : AppLockAction
    data object OnAppShown : AppLockAction
}

/**
 * Locks the app at start and when it comes back after the configured time away, until the PIN or a fingerprint
 * unlocks it.
 */
class AppLockViewModel(
    private val repository: AppLockRepository,
    private val biometrics: BiometricAuthenticator,
) : ViewModel() {

    private val _state = MutableStateFlow(AppLockState())
    val state = _state.asStateFlow()

    private var settings = AppLockSettings()
    private var pin = ""
    private var hiddenAtMillis: Long? = null
    private var waitJob: Job? = null
    private var biometricJob: Job? = null

    init {
        viewModelScope.launch {
            var first = true
            repository.observeSettings().collect { latest ->
                settings = latest
                _state.update {
                    it.copy(
                        isReady = true,
                        // Locked at start; turning the lock on while using the app doesn't lock it right away
                        isLocked = if (first) latest.isEnabled else it.isLocked && latest.isEnabled,
                        canUseBiometric = latest.isBiometricEnabled && biometrics.isAvailable(),
                    )
                }
                if (first && latest.isEnabled) promptBiometric()
                first = false
            }
        }
    }

    fun onAction(action: AppLockAction) {
        when (action) {
            is AppLockAction.OnDigit -> if (pin.length < PIN_MAX_LENGTH && canType()) {
                pin += action.digit
                _state.update { it.copy(pinLength = pin.length, message = null, isError = false) }
            }

            AppLockAction.OnDelete -> if (pin.isNotEmpty()) {
                pin = pin.dropLast(1)
                _state.update { it.copy(pinLength = pin.length) }
            }

            AppLockAction.OnSubmit -> submit()
            AppLockAction.OnBiometricClick -> promptBiometric()
            AppLockAction.OnAppHidden -> hiddenAtMillis = now()
            AppLockAction.OnAppShown -> {
                val hiddenAt = hiddenAtMillis ?: return
                hiddenAtMillis = null
                val hiddenLongEnough = now() - hiddenAt >= settings.lockAfter.duration.inWholeMilliseconds
                if (settings.isEnabled && !_state.value.isLocked && hiddenLongEnough) {
                    lock()
                }
            }
        }
    }

    private fun canType() = !_state.value.isChecking && _state.value.waitSeconds == 0L

    private fun lock() {
        pin = ""
        _state.update { it.copy(isLocked = true, pinLength = 0, message = null, isError = false) }
        promptBiometric()
    }

    private fun unlock() {
        pin = ""
        biometricJob?.cancel()
        _state.update { it.copy(isLocked = false, pinLength = 0, message = null, isError = false, isChecking = false) }
    }

    private fun submit() {
        if (pin.length < PIN_MIN_LENGTH || !canType()) return
        val entered = pin
        _state.update { it.copy(isChecking = true) }
        viewModelScope.launch {
            when (val result = repository.checkPin(entered)) {
                PinCheck.Correct -> unlock()
                is PinCheck.Wrong -> {
                    pin = ""
                    _state.update {
                        it.copy(
                            isChecking = false, pinLength = 0, isError = true,
                            message = if (result.attemptsBeforeWait <= 2) {
                                UiText.Resource(Res.string.pin_wrong_tries_left, arrayOf(result.attemptsBeforeWait))
                            } else {
                                UiText.Resource(Res.string.pin_wrong)
                            }
                        )
                    }
                }

                is PinCheck.TooManyAttempts -> {
                    pin = ""
                    _state.update { it.copy(isChecking = false, pinLength = 0) }
                    startWait(result.seconds)
                }
            }
        }
    }

    private fun startWait(seconds: Long) {
        waitJob?.cancel()
        waitJob = viewModelScope.launch {
            for (left in seconds downTo 1) {
                _state.update {
                    it.copy(
                        waitSeconds = left,
                        isError = true,
                        message = UiText.Resource(Res.string.pin_too_many, arrayOf(left))
                    )
                }
                delay(1.seconds)
            }
            _state.update { it.copy(waitSeconds = 0, isError = false, message = null) }
        }
    }

    private fun promptBiometric() {
        if (!_state.value.canUseBiometric || biometricJob?.isActive == true) return
        biometricJob = viewModelScope.launch {
            if (biometrics.authenticate(
                    title = getString(Res.string.bio_unlock_title),
                    subtitle = getString(Res.string.bio_unlock_sub)
                )
            ) unlock()
        }
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()
}
