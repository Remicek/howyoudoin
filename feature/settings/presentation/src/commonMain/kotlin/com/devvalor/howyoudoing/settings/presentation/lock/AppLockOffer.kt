package com.devvalor.howyoudoing.settings.presentation.lock

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.settings.domain.AppLockRepository
import com.devvalor.howyoudoing.settings.domain.BiometricAuthenticator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Asks once, after signing in, whether to lock the app; it stays off unless the user chooses it. */
class AppLockOfferViewModel(
    private val repository: AppLockRepository,
    biometrics: BiometricAuthenticator,
) : ViewModel() {

    val hasFingerprint = biometrics.isAvailable()

    val shouldOffer = combine(repository.observeLockOffered(), repository.observeSettings()) { offered, settings ->
        !offered && !settings.isEnabled
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun answer() {
        viewModelScope.launch { repository.setLockOffered() }
    }
}

@Composable
fun AppLockOfferRoot(
    onSetUp: () -> Unit,
    viewModel: AppLockOfferViewModel = koinViewModel(),
) {
    val shouldOffer by viewModel.shouldOffer.collectAsStateWithLifecycle()
    if (!shouldOffer) return
    AlertDialog(
        onDismissRequest = viewModel::answer,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text("Lock HowYouDoin?") },
        text = {
            Text(
                if (viewModel.hasFingerprint) {
                    "Ask for your fingerprint or a PIN before your chats are shown. You can change this any time in Settings → App lock."
                } else {
                    "Ask for a PIN before your chats are shown. You can change this any time in Settings → App lock."
                }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.answer()
                onSetUp()
            }) { Text("Set up") }
        },
        dismissButton = { TextButton(onClick = viewModel::answer) { Text("Not now") } },
    )
}
