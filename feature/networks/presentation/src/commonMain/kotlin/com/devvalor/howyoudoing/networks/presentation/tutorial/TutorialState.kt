package com.devvalor.howyoudoing.networks.presentation.tutorial

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

data class TutorialState(
    val network: ChatNetwork,
    val pages: List<TutorialPage> = TutorialPages.forNetwork(network),
    val currentPage: Int = 0,
    val dontShowAgain: Boolean = false
) {
    val isLastPage: Boolean get() = currentPage >= pages.lastIndex
}
