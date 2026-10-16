package com.devvalor.howyoudoing.networks.presentation.tutorial

sealed interface TutorialEvent {
    /** @param flowId login flow to start, null = let the user pick on the connect screen */
    data class Finish(val flowId: String?) : TutorialEvent
}
