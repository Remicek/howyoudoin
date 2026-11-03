package com.devvalor.howyoudoing.networks.data.demo

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.networks.domain.BridgeLoginFlow
import com.devvalor.howyoudoing.networks.domain.BridgeLoginStep
import com.devvalor.howyoudoing.networks.domain.BridgeRepository
import com.devvalor.howyoudoing.networks.domain.BridgeStatus
import com.devvalor.howyoudoing.networks.domain.DisplayType
import com.devvalor.howyoudoing.networks.domain.EncryptedHistoryStatus
import com.devvalor.howyoudoing.networks.domain.HistoryRestoreError
import com.devvalor.howyoudoing.networks.domain.InputFieldType
import com.devvalor.howyoudoing.networks.domain.LoginInputField
import com.devvalor.howyoudoing.networks.domain.NetworkLogin
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

/** Simulates bridge login flows so the connect UI can be tried without real bridges. */
class DemoBridgeRepository : BridgeRepository {

    private val logins = MutableStateFlow(
        mapOf(
            ChatNetwork.WHATSAPP to listOf(NetworkLogin("demo-wa", "+48 600 100 200", "CONNECTED")),
            ChatNetwork.MESSENGER to listOf(NetworkLogin("demo-fb", "you.demo", "CONNECTED")),
        )
    )

    override fun observeIsConfigured(network: ChatNetwork): Flow<Boolean> = flowOf(true)
    override fun observeBridgeUrl(network: ChatNetwork): Flow<String?> = flowOf("demo")
    override suspend fun setBridgeUrl(network: ChatNetwork, url: String) = Unit
    override suspend fun hasBridgeServer(): Boolean = false

    override suspend fun getStatus(network: ChatNetwork): Result<BridgeStatus, DataError.Remote> {
        delay(300.milliseconds)
        return Result.Success(
            BridgeStatus(
                network = network,
                flows = flowsFor(network),
                logins = logins.value[network].orEmpty()
            )
        )
    }

    override suspend fun startLogin(network: ChatNetwork, flowId: String): Result<BridgeLoginStep, DataError.Remote> {
        delay(400.milliseconds)
        val loginId = "demo-login-${DEMO_CODES.random()}"
        return Result.Success(
            when (flowId) {
                "qr" -> qrStep(loginId)
                "phone" -> BridgeLoginStep.UserInput(
                    loginId = loginId,
                    stepId = "phone",
                    instructions = "Enter the phone number of your WhatsApp account.",
                    fields = listOf(
                        LoginInputField(
                            "phone_number",
                            "Phone number",
                            "Include the country code",
                            InputFieldType.PHONE_NUMBER
                        )
                    )
                )

                else -> BridgeLoginStep.UserInput(
                    loginId = loginId,
                    stepId = "credentials",
                    instructions = "Demo mode: any credentials work. " +
                        "Real Messenger bridges use a browser login instead.",
                    fields = listOf(
                        LoginInputField("email", "Email or phone", "", InputFieldType.EMAIL),
                        LoginInputField("password", "Password", "", InputFieldType.PASSWORD),
                    )
                )
            }
        )
    }

    override suspend fun waitForStep(
        network: ChatNetwork,
        step: BridgeLoginStep.DisplayAndWait
    ): Result<BridgeLoginStep, DataError.Remote> {
        delay(5.seconds)
        return complete(network, step.loginId)
    }

    override suspend fun submitUserInput(
        network: ChatNetwork,
        step: BridgeLoginStep.UserInput,
        values: Map<String, String>
    ): Result<BridgeLoginStep, DataError.Remote> {
        delay(600.milliseconds)
        return if (step.stepId == "phone") {
            Result.Success(
                BridgeLoginStep.DisplayAndWait(
                    loginId = step.loginId,
                    stepId = "pairing-code",
                    instructions = "Open WhatsApp → Linked devices → Link with phone number, then enter this code.",
                    displayType = DisplayType.CODE,
                    data = "RLAY-${DEMO_CODES.random()}"
                )
            )
        } else {
            complete(network, step.loginId, name = values["email"])
        }
    }

    override suspend fun submitCookies(
        network: ChatNetwork,
        step: BridgeLoginStep.Cookies,
        values: Map<String, String>
    ): Result<BridgeLoginStep, DataError.Remote> = complete(network, step.loginId)

    override suspend fun logout(network: ChatNetwork, loginId: String): EmptyResult<DataError.Remote> {
        logins.update { all -> all + (network to all[network].orEmpty().filterNot { it.id == loginId }) }
        return Result.Success(Unit)
    }

    override suspend fun getEncryptedHistoryStatus(
        network: ChatNetwork
    ): Result<EncryptedHistoryStatus, DataError.Remote> =
        Result.Success(EncryptedHistoryStatus(isRestored = false, isBackupAvailable = false))

    override suspend fun restoreEncryptedHistory(network: ChatNetwork, pin: String): EmptyResult<HistoryRestoreError> =
        Result.Failure(HistoryRestoreError.NoBackup)

    private fun complete(
        network: ChatNetwork,
        loginId: String,
        name: String? = null
    ): Result<BridgeLoginStep, DataError.Remote> {
        val login = NetworkLogin(
            loginId,
            name?.takeIf { it.isNotBlank() } ?: "Demo ${network.displayName} account",
            "CONNECTED")
        logins.update { all -> all + (network to all[network].orEmpty() + login) }
        return Result.Success(
            BridgeLoginStep.Complete(
                loginId = loginId,
                stepId = "complete",
                instructions = "Successfully logged in",
                userLoginId = loginId
            )
        )
    }

    private fun qrStep(loginId: String) = BridgeLoginStep.DisplayAndWait(
        loginId = loginId,
        stepId = "qr",
        instructions = "Open WhatsApp on your phone → Settings → Linked devices → Link a device, " +
            "then scan this code. (Demo: completes automatically in a few seconds.)",
        displayType = DisplayType.QR,
        data = "2@howyoudoin-demo-${Random.nextLong()}"
    )

    private fun flowsFor(network: ChatNetwork): List<BridgeLoginFlow> = when (network) {
        ChatNetwork.WHATSAPP -> listOf(
            BridgeLoginFlow("qr", "QR code", "Scan a QR code with the WhatsApp app on your phone"),
            BridgeLoginFlow("phone", "Pairing code", "Link with your phone number instead of a QR code"),
        )

        ChatNetwork.MESSENGER -> listOf(
            BridgeLoginFlow("messenger", "Messenger", "Log in with your Messenger / Facebook account"),
        )

        else -> emptyList()
    }
}

private val DEMO_CODES = 1000..9999
