package com.devvalor.howyoudoing.networks.data

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.networks.domain.BridgeLoginStep
import com.devvalor.howyoudoing.networks.domain.BridgeRepository
import com.devvalor.howyoudoing.networks.domain.BridgeStatus
import com.devvalor.howyoudoing.networks.domain.EncryptedHistoryStatus
import com.devvalor.howyoudoing.networks.domain.HistoryRestoreError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** No server bridges in "on this phone only" sessions: networks offer just their on-device connections. */
class LocalOnlyBridgeRepository : BridgeRepository {
    override fun observeIsConfigured(network: ChatNetwork): Flow<Boolean> = flowOf(true)
    override fun observeBridgeUrl(network: ChatNetwork): Flow<String?> = flowOf(null)
    override suspend fun setBridgeUrl(network: ChatNetwork, url: String) = Unit
    override suspend fun getStatus(network: ChatNetwork): Result<BridgeStatus, DataError.Remote> =
        Result.Success(BridgeStatus(network, flows = emptyList(), logins = emptyList()))
    override suspend fun startLogin(network: ChatNetwork, flowId: String): Result<BridgeLoginStep, DataError.Remote> = noServer()
    override suspend fun waitForStep(network: ChatNetwork, step: BridgeLoginStep.DisplayAndWait): Result<BridgeLoginStep, DataError.Remote> = noServer()
    override suspend fun submitUserInput(
        network: ChatNetwork, step: BridgeLoginStep.UserInput, values: Map<String, String>
    ): Result<BridgeLoginStep, DataError.Remote> = noServer()
    override suspend fun submitCookies(
        network: ChatNetwork, step: BridgeLoginStep.Cookies, values: Map<String, String>
    ): Result<BridgeLoginStep, DataError.Remote> = noServer()
    override suspend fun logout(network: ChatNetwork, loginId: String): EmptyResult<DataError.Remote> = Result.Success(Unit)
    override suspend fun getEncryptedHistoryStatus(network: ChatNetwork): Result<EncryptedHistoryStatus, DataError.Remote> =
        Result.Success(EncryptedHistoryStatus(isRestored = false, isBackupAvailable = false))
    override suspend fun restoreEncryptedHistory(network: ChatNetwork, pin: String): EmptyResult<HistoryRestoreError> =
        Result.Failure(HistoryRestoreError.NoBackup)

    private fun noServer(): Result<BridgeLoginStep, DataError.Remote> = Result.Failure(DataError.Remote.BAD_REQUEST)
}
