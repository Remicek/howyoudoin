package com.devvalor.howyoudoing.networks.presentation.history

data class EncryptedHistoryState(
    /** Messenger has a PIN backup that hasn't been restored, and the inbox has encrypted Messenger chats. */
    val isOffered: Boolean = false,
    val isDialogOpen: Boolean = false,
    val pin: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    /** Restore accepted; encrypted chats are being rebuilt with their history. */
    val isRestoring: Boolean = false
) {
    val canSubmit: Boolean get() = pin.length == PIN_LENGTH && !isSubmitting

    companion object {
        const val PIN_LENGTH = 6
    }
}
