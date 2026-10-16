package com.devvalor.howyoudoing.app

sealed interface MainEvent {
    data object OnSessionEnded : MainEvent
}
