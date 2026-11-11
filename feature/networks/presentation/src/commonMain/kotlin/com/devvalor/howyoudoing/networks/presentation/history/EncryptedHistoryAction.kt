package com.devvalor.howyoudoing.networks.presentation.history

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

sealed interface EncryptedHistoryAction {
    data class OnEncryptedNetworksChange(val networks: Set<ChatNetwork>) : EncryptedHistoryAction
    data object OnEnterPinClick : EncryptedHistoryAction
    data object OnNotNowClick : EncryptedHistoryAction
    data object OnDontShowAgainClick : EncryptedHistoryAction
    data class OnPinChange(val pin: String) : EncryptedHistoryAction
    data object OnRestoreClick : EncryptedHistoryAction
    data object OnDismissDialog : EncryptedHistoryAction
    data object OnDismissRestoring : EncryptedHistoryAction
}
