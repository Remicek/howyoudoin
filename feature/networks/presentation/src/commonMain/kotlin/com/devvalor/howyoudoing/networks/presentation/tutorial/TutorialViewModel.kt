package com.devvalor.howyoudoing.networks.presentation.tutorial

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.networks.domain.OnboardingRepository
import com.devvalor.howyoudoing.networks.presentation.navigation.NetworksRoutes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TutorialViewModel(
    savedStateHandle: SavedStateHandle,
    private val onboardingRepository: OnboardingRepository
) : ViewModel() {

    private val network = ChatNetwork.valueOf(savedStateHandle.toRoute<NetworksRoutes.Tutorial>().network)

    private val eventChannel = Channel<TutorialEvent>()
    val events = eventChannel.receiveAsFlow()

    private val _state = MutableStateFlow(TutorialState(network = network))
    val state = _state.asStateFlow()

    fun onAction(action: TutorialAction) {
        when (action) {
            is TutorialAction.OnPageChange -> _state.update { it.copy(currentPage = action.page.coerceIn(0, it.pages.lastIndex)) }
            TutorialAction.OnNextClick -> _state.update { it.copy(currentPage = (it.currentPage + 1).coerceAtMost(it.pages.lastIndex)) }
            TutorialAction.OnPreviousClick -> _state.update { it.copy(currentPage = (it.currentPage - 1).coerceAtLeast(0)) }
            TutorialAction.OnDontShowAgainToggle -> _state.update { it.copy(dontShowAgain = !it.dontShowAgain) }
            TutorialAction.OnSkipClick -> finish(flowId = null)
            is TutorialAction.OnOptionClick -> finish(flowId = action.flowId)
            TutorialAction.OnBackClick -> Unit
        }
    }

    private fun finish(flowId: String?) {
        viewModelScope.launch {
            if (state.value.dontShowAgain) onboardingRepository.setTutorialDismissed(network, dismissed = true)
            eventChannel.send(TutorialEvent.Finish(flowId))
        }
    }
}
