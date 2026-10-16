package com.relay.networks.presentation.list

import com.relay.core.presentation.util.UiText

sealed interface NetworksEvent {
    data class Error(val message: UiText) : NetworksEvent
}
