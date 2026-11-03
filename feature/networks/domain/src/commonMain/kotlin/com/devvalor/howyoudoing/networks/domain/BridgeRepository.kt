package com.devvalor.howyoudoing.networks.domain

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.flow.Flow

interface BridgeRepository {
    /** Whether the bridge for [network] can be used without asking for its URL first. */
    fun observeIsConfigured(network: ChatNetwork): Flow<Boolean>
    fun observeBridgeUrl(network: ChatNetwork): Flow<String?>
    suspend fun setBridgeUrl(network: ChatNetwork, url: String)

    /** Whether there is a bridge server whose URL [setBridgeUrl] changes (not for on-device or demo sessions). */
    suspend fun hasBridgeServer(): Boolean = true

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

    /** Only Messenger has a PIN-protected history backup; other networks report nothing to restore. */
    suspend fun getEncryptedHistoryStatus(network: ChatNetwork): Result<EncryptedHistoryStatus, DataError.Remote>

    /** Unlocks the backup with [pin]; the bridge then rebuilds encrypted chats with their history. */
    suspend fun restoreEncryptedHistory(network: ChatNetwork, pin: String): EmptyResult<HistoryRestoreError>
}
