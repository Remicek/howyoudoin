package com.relay.networks.domain

import com.relay.core.domain.network.ChatNetwork
import com.relay.core.domain.util.DataError
import com.relay.core.domain.util.EmptyResult
import com.relay.core.domain.util.Result
import kotlinx.coroutines.flow.Flow

interface BridgeRepository {
    /** Whether the bridge for [network] can be used without asking for its URL first. */
    fun observeIsConfigured(network: ChatNetwork): Flow<Boolean>
    fun observeBridgeUrl(network: ChatNetwork): Flow<String?>
    suspend fun setBridgeUrl(network: ChatNetwork, url: String)

    suspend fun getStatus(network: ChatNetwork): Result<BridgeStatus, DataError.Remote>

    suspend fun startLogin(network: ChatNetwork, flowId: String): Result<BridgeLoginStep, DataError.Remote>

    /** Long-polls until the user acts on a [BridgeLoginStep.DisplayAndWait] step. */
    suspend fun waitForStep(
        network: ChatNetwork,
        step: BridgeLoginStep.DisplayAndWait
    ): Result<BridgeLoginStep, DataError.Remote>

    suspend fun submitUserInput(
        network: ChatNetwork,
        step: BridgeLoginStep.UserInput,
        values: Map<String, String>
    ): Result<BridgeLoginStep, DataError.Remote>

    suspend fun submitCookies(
        network: ChatNetwork,
        step: BridgeLoginStep.Cookies,
        values: Map<String, String>
    ): Result<BridgeLoginStep, DataError.Remote>

    suspend fun logout(network: ChatNetwork, loginId: String): EmptyResult<DataError.Remote>
}
