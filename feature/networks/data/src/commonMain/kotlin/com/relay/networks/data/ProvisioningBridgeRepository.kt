package com.relay.networks.data

import com.relay.core.data.networking.safeCall
import com.relay.core.domain.auth.AuthInfo
import com.relay.core.domain.auth.SessionStorage
import com.relay.core.domain.network.ChatNetwork
import com.relay.core.domain.util.DataError
import com.relay.core.domain.util.EmptyResult
import com.relay.core.domain.util.Result
import com.relay.core.domain.util.asEmptyResult
import com.relay.core.domain.util.map
import com.relay.networks.data.dto.LoginStepDto
import com.relay.networks.data.dto.WhoamiResponseDto
import com.relay.networks.data.mappers.toBridgeStatus
import com.relay.networks.data.mappers.toDomain
import com.relay.networks.domain.BridgeLoginStep
import com.relay.networks.domain.BridgeRepository
import com.relay.networks.domain.BridgeStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import kotlinx.coroutines.flow.Flow
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
        endpointStorage.observe(network).map { !it.isNullOrBlank() }

    override fun observeBridgeUrl(network: ChatNetwork): Flow<String?> = endpointStorage.observe(network)

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
        val baseUrl = endpointStorage.get(network) ?: return Result.Failure(DataError.Remote.NOT_FOUND)
        return block(session, baseUrl)
    }

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
