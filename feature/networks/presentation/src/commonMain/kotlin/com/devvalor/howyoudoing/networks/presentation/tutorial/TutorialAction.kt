package com.devvalor.howyoudoing.networks.presentation.tutorial

sealed interface TutorialAction {
    data class OnPageChange(val page: Int) : TutorialAction
    data object OnNextClick : TutorialAction
    data object OnPreviousClick : TutorialAction
    data object OnSkipClick : TutorialAction
    data object OnDontShowAgainToggle : TutorialAction
    data class OnOptionClick(val flowId: String) : TutorialAction
    data object OnBackClick : TutorialAction
}
