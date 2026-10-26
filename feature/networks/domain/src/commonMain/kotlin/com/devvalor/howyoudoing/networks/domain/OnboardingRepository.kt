package com.devvalor.howyoudoing.networks.domain

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlinx.coroutines.flow.Flow

/** Remembers which connection tutorials the user chose not to see again, and the one-time risk warning. */
interface OnboardingRepository {
    fun observeDismissedTutorials(): Flow<Set<ChatNetwork>>
    suspend fun setTutorialDismissed(network: ChatNetwork, dismissed: Boolean)

    /** The user accepted, before connecting their first network, that networks may block unofficial apps. */
    fun observeRiskAccepted(): Flow<Boolean>
    suspend fun setRiskAccepted()
}
