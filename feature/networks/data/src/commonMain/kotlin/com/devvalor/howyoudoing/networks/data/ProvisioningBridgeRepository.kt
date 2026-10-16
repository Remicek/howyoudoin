package com.devvalor.howyoudoing.networks.data

import com.devvalor.howyoudoing.core.data.networking.safeCall
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.auth.SessionStorage
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.domain.util.asEmptyResult
import com.devvalor.howyoudoing.core.domain.util.map
import com.devvalor.howyoudoing.core.data.networking.responseToResult
import com.devvalor.howyoudoing.networks.data.dto.HistoryErrorDto
import com.devvalor.howyoudoing.networks.data.dto.HistoryStatusDto
import com.devvalor.howyoudoing.networks.data.dto.LoginStepDto
import com.devvalor.howyoudoing.networks.domain.EncryptedHistoryStatus
import com.devvalor.howyoudoing.networks.domain.HistoryRestoreError
import io.ktor.client.call.body
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import com.devvalor.howyoudoing.networks.data.dto.WhoamiResponseDto
import com.devvalor.howyoudoing.networks.data.mappers.toBridgeStatus
import com.devvalor.howyoudoing.networks.data.mappers.toDomain
import com.devvalor.howyoudoing.networks.domain.BridgeLoginStep
import com.devvalor.howyoudoing.networks.domain.BridgeRepository
import com.devvalor.howyoudoing.networks.domain.BridgeStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import com.devvalor.howyoudoing.core.domain.config.BackendConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

/**
 * Talks to a mautrix bridgev2 provisioning API (mautrix-whatsapp, mautrix-meta, ...).
 * Requests are authenticated with the user's Matrix access token, which bridgev2 accepts
 * when `provisioning.allow_matrix_auth` is enabled (the default).
 */
class ProvisioningBridgeRepository(
    private val httpClient: HttpClient,
    private val sessionStorage: SessionStorage,
    private val endpointStorage: BridgeEndpointStorage
) : BridgeRepository {

    override fun observeIsConfigured(network: ChatNetwork): Flow<Boolean> =
        observeBridgeUrl(network).map { !it.isNullOrBlank() }

    /** A URL saved by the user wins, otherwise the bridge is assumed to live next to the homeserver. */
    override fun observeBridgeUrl(network: ChatNetwork): Flow<String?> =
        combine(endpointStorage.observe(network), sessionStorage.observeAuthInfo()) { saved, session ->
            saved?.takeIf { it.isNotBlank() }
                ?: session?.homeserverUrl?.let { BackendConfig.defaultBridgeUrl(it, network) }
        }

    override suspend fun setBridgeUrl(network: ChatNetwork, url: String) = endpointStorage.set(network, url)

    override suspend fun getStatus(network: ChatNetwork): Result<BridgeStatus, DataError.Remote> =
        request(network) { session, baseUrl ->
            safeCall<WhoamiResponseDto> {
                httpClient.get { provisioning(session, baseUrl, "whoami") }
            }.map { it.toBridgeStatus(network) }
        }

    override suspend fun startLogin(network: ChatNetwork, flowId: String): Result<BridgeLoginStep, DataError.Remote> =
        request(network) { session, baseUrl ->
            safeCall<LoginStepDto> {
                httpClient.post {
                    provisioning(session, baseUrl, "login", "start", flowId)
                    setBody(JsonObject(emptyMap()))
                }
            }.toStep()
        }

    override suspend fun waitForStep(
        network: ChatNetwork,
        step: BridgeLoginStep.DisplayAndWait
    ): Result<BridgeLoginStep, DataError.Remote> = submitStep(network, step, "display_and_wait", emptyMap())

    override suspend fun submitUserInput(
        network: ChatNetwork,
        step: BridgeLoginStep.UserInput,
        values: Map<String, String>
    ): Result<BridgeLoginStep, DataError.Remote> = submitStep(network, step, "user_input", values)

    override suspend fun submitCookies(
        network: ChatNetwork,
        step: BridgeLoginStep.Cookies,
        values: Map<String, String>
    ): Result<BridgeLoginStep, DataError.Remote> = submitStep(network, step, "cookies", values)

    override suspend fun logout(network: ChatNetwork, loginId: String): EmptyResult<DataError.Remote> =
        request(network) { session, baseUrl ->
            safeCall<JsonObject> {
                httpClient.post {
                    provisioning(session, baseUrl, "logout", loginId)
                    setBody(JsonObject(emptyMap()))
                }
            }.asEmptyResult()
        }

    override suspend fun getEncryptedHistoryStatus(network: ChatNetwork): Result<EncryptedHistoryStatus, DataError.Remote> {
        if (network != ChatNetwork.MESSENGER) return Result.Success(EncryptedHistoryStatus(isRestored = false, isBackupAvailable = false))
        return request(network) { session, baseUrl ->
            safeCall<HistoryStatusDto> {
                httpClient.get { provisioning(session, baseUrl, "messenger", "history") }
            }.map { EncryptedHistoryStatus(isRestored = it.restored, isBackupAvailable = it.backupAvailable) }
        }
    }

    override suspend fun restoreEncryptedHistory(network: ChatNetwork, pin: String): EmptyResult<HistoryRestoreError> {
        if (network != ChatNetwork.MESSENGER) return Result.Failure(HistoryRestoreError.NoBackup)
        val session = sessionStorage.get() ?: return Result.Failure(HistoryRestoreError.Remote(DataError.Remote.UNAUTHORIZED))
        val response = try {
            httpClient.post {
                provisioning(session, bridgeUrl(session, network), "messenger", "history", "restore")
                setBody(mapOf("pin" to pin))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Failure(HistoryRestoreError.Remote(DataError.Remote.UNKNOWN))
        }
        if (response.status.isSuccess()) return Result.Success(Unit)
        // The bridge explains PIN problems in the Matrix-style error body
        val error = runCatching { response.body<HistoryErrorDto>() }.getOrNull()
        return Result.Failure(
            when (error?.errcode) {
                "FI.MAU.META.HISTORY_WRONG_PIN" -> HistoryRestoreError.WrongPin(error.attemptsRemaining ?: 0)
                "FI.MAU.META.HISTORY_PIN_LIMIT" -> HistoryRestoreError.NoAttemptsLeft
                "FI.MAU.META.HISTORY_NO_BACKUP" -> HistoryRestoreError.NoBackup
                "FI.MAU.META.TOKEN_INVALIDATED" -> HistoryRestoreError.SessionExpired
                else -> HistoryRestoreError.Remote(
                    responseToResult<Unit>(response).let { (it as? Result.Failure)?.error ?: DataError.Remote.UNKNOWN }
                )
            }
        )
    }

    private suspend fun submitStep(
        network: ChatNetwork,
        step: BridgeLoginStep,
        stepType: String,
        values: Map<String, String>
    ): Result<BridgeLoginStep, DataError.Remote> =
        request(network) { session, baseUrl ->
            safeCall<LoginStepDto> {
                httpClient.post {
                    provisioning(session, baseUrl, "login", "step", step.loginId, step.stepId, stepType)
                    setBody(values)
                }
            }.toStep()
        }

    private suspend fun <T> request(
        network: ChatNetwork,
        block: suspend (session: AuthInfo, baseUrl: String) -> Result<T, DataError.Remote>
    ): Result<T, DataError.Remote> {
        val session = sessionStorage.get() ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        return block(session, bridgeUrl(session, network))
    }

    private suspend fun bridgeUrl(session: AuthInfo, network: ChatNetwork): String =
        endpointStorage.get(network)?.takeIf { it.isNotBlank() }
            ?: BackendConfig.defaultBridgeUrl(session.homeserverUrl, network)

    private fun HttpRequestBuilder.provisioning(session: AuthInfo, baseUrl: String, vararg path: String) {
        url {
            takeFrom(baseUrl)
            appendPathSegments("_matrix", "provision", "v3", *path)
        }
        bearerAuth(session.accessToken)
    }

    private fun Result<LoginStepDto, DataError.Remote>.toStep(): Result<BridgeLoginStep, DataError.Remote> =
        when (this) {
            is Result.Failure -> this
            is Result.Success -> data.toDomain()?.let { Result.Success(it) }
                ?: Result.Failure(DataError.Remote.SERIALIZATION)
        }
}
