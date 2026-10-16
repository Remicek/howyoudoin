package com.relay.app

sealed interface MainEvent {
    data object OnSessionEnded : MainEvent
}
