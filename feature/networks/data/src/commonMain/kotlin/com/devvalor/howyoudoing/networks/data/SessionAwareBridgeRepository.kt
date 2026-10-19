package com.devvalor.howyoudoing.networks.data

import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.auth.SessionStorage
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.networks.data.demo.DemoBridgeRepository
import com.devvalor.howyoudoing.networks.domain.BridgeLoginStep
import com.devvalor.howyoudoing.networks.domain.BridgeRepository
import com.devvalor.howyoudoing.networks.domain.EncryptedHistoryStatus
import com.devvalor.howyoudoing.networks.domain.HistoryRestoreError
import com.devvalor.howyoudoing.networks.domain.BridgeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Routes to the simulated bridges for demo sessions and to real provisioning APIs otherwise. */
class SessionAwareBridgeRepository(
    private val provisioning: ProvisioningBridgeRepository,
    private val local: LocalOnlyBridgeRepository,
    private val demo: DemoBridgeRepository,
    private val sessionStorage: SessionStorage
) : BridgeRepository {

    private fun <T> select(block: BridgeRepository.() -> Flow<T>): Flow<T> =
        sessionStorage.observeAuthInfo()
            .map { it.kind() }
            .distinctUntilChanged()
            .flatMapLatest { kind -> repositoryFor(kind).block() }

    private suspend fun current(): BridgeRepository =
        repositoryFor(sessionStorage.get().kind())

    private enum class Kind { SERVER, DEMO, LOCAL }

    private fun AuthInfo?.kind() = when {
        this?.isDemo == true -> Kind.DEMO
        this?.isLocal == true -> Kind.LOCAL
        else -> Kind.SERVER
    }

    private fun repositoryFor(kind: Kind) = when (kind) {
        Kind.SERVER -> provisioning
        Kind.DEMO -> demo
        Kind.LOCAL -> local
    }

    override fun observeIsConfigured(network: ChatNetwork): Flow<Boolean> = select { observeIsConfigured(network) }
    override fun observeBridgeUrl(network: ChatNetwork): Flow<String?> = select { observeBridgeUrl(network) }
    override suspend fun setBridgeUrl(network: ChatNetwork, url: String) = current().setBridgeUrl(network, url)

    override suspend fun getStatus(network: ChatNetwork): Result<BridgeStatus, DataError.Remote> =
        current().getStatus(network)

    override suspend fun startLogin(network: ChatNetwork, flowId: String): Result<BridgeLoginStep, DataError.Remote> =
        current().startLogin(network, flowId)

    override suspend fun waitForStep(
        network: ChatNetwork,
        step: BridgeLoginStep.DisplayAndWait
    ): Result<BridgeLoginStep, DataError.Remote> = current().waitForStep(network, step)

    override suspend fun submitUserInput(
        network: ChatNetwork,
        step: BridgeLoginStep.UserInput,
        values: Map<String, String>
    ): Result<BridgeLoginStep, DataError.Remote> = current().submitUserInput(network, step, values)

    override suspend fun submitCookies(
        network: ChatNetwork,
        step: BridgeLoginStep.Cookies,
        values: Map<String, String>
    ): Result<BridgeLoginStep, DataError.Remote> = current().submitCookies(network, step, values)

    override suspend fun logout(network: ChatNetwork, loginId: String): EmptyResult<DataError.Remote> =
        current().logout(network, loginId)

    override suspend fun getEncryptedHistoryStatus(network: ChatNetwork): Result<EncryptedHistoryStatus, DataError.Remote> =
        current().getEncryptedHistoryStatus(network)

    override suspend fun restoreEncryptedHistory(network: ChatNetwork, pin: String): EmptyResult<HistoryRestoreError> =
        current().restoreEncryptedHistory(network, pin)
}
