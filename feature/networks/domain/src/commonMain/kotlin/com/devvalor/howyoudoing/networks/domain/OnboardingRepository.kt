package com.devvalor.howyoudoing.networks.domain

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlinx.coroutines.flow.Flow

/** Remembers which connection tutorials the user chose not to see again. */
interface OnboardingRepository {
    fun observeDismissedTutorials(): Flow<Set<ChatNetwork>>
    suspend fun setTutorialDismissed(network: ChatNetwork, dismissed: Boolean)
}
