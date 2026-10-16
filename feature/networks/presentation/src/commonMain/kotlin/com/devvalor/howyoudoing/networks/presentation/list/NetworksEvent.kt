package com.devvalor.howyoudoing.networks.presentation.list

import com.devvalor.howyoudoing.core.presentation.util.UiText

sealed interface NetworksEvent {
    data class Error(val message: UiText) : NetworksEvent
}
