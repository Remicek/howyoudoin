package com.devvalor.howyoudoing.networks.presentation.connect

import howyoudoin.feature.networks.presentation.generated.resources.field_x_passcode
import howyoudoin.feature.networks.presentation.generated.resources.x_passcode_instructions
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_x
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_gvoice
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_discord
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_linkedin
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_bluesky
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_gmessages_google
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_gmessages_qr
import howyoudoin.feature.networks.presentation.generated.resources.flow_google_account
import howyoudoin.feature.networks.presentation.generated.resources.pair_gmessages_emoji
import howyoudoin.feature.networks.presentation.generated.resources.pair_gmessages_qr
import howyoudoin.feature.networks.presentation.generated.resources.cookies_gmessages
import howyoudoin.feature.networks.presentation.generated.resources.cookies_gvoice
import howyoudoin.feature.networks.presentation.generated.resources.cookies_discord
import howyoudoin.feature.networks.presentation.generated.resources.cookies_linkedin
import howyoudoin.feature.networks.presentation.generated.resources.field_app_password
import howyoudoin.feature.networks.presentation.generated.resources.field_bluesky_handle
import howyoudoin.feature.networks.presentation.generated.resources.bluesky_instructions
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import howyoudoin.feature.networks.presentation.generated.resources.flow_device_phone
import howyoudoin.feature.networks.presentation.generated.resources.flow_device_qr
import howyoudoin.feature.networks.presentation.generated.resources.flow_device_code
import howyoudoin.feature.networks.presentation.generated.resources.flow_matrix
import howyoudoin.feature.networks.presentation.generated.resources.flow_device
import howyoudoin.feature.networks.presentation.generated.resources.field_phone
import howyoudoin.feature.networks.presentation.generated.resources.telegram_phone_instructions
import howyoudoin.feature.networks.presentation.generated.resources.tg_code_retry
import howyoudoin.feature.networks.presentation.generated.resources.tg_code_app
import howyoudoin.feature.networks.presentation.generated.resources.tg_code_sms
import howyoudoin.feature.networks.presentation.generated.resources.tg_code_email
import howyoudoin.feature.networks.presentation.generated.resources.tg_code_call
import howyoudoin.feature.networks.presentation.generated.resources.tg_code_fragment
import howyoudoin.feature.networks.presentation.generated.resources.tg_code_other
import howyoudoin.feature.networks.presentation.generated.resources.tg_password_retry
import howyoudoin.feature.networks.presentation.generated.resources.tg_password
import howyoudoin.feature.networks.presentation.generated.resources.tg_password_hint
import howyoudoin.feature.networks.presentation.generated.resources.field_login_code
import howyoudoin.feature.networks.presentation.generated.resources.field_password
import howyoudoin.feature.networks.presentation.generated.resources.pair_signal
import howyoudoin.feature.networks.presentation.generated.resources.pair_telegram
import howyoudoin.feature.networks.presentation.generated.resources.pair_whatsapp_code
import howyoudoin.feature.networks.presentation.generated.resources.pair_whatsapp_qr
import howyoudoin.feature.networks.presentation.generated.resources.login_failed_retry
import howyoudoin.feature.networks.presentation.generated.resources.cookies_messenger
import howyoudoin.feature.networks.presentation.generated.resources.cookies_instagram
import howyoudoin.feature.networks.presentation.generated.resources.cookies_slack
import howyoudoin.feature.networks.presentation.generated.resources.matrix_instructions
import howyoudoin.feature.networks.presentation.generated.resources.field_homeserver
import howyoudoin.feature.networks.presentation.generated.resources.field_matrix_user
import howyoudoin.feature.networks.presentation.generated.resources.Res
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_connect_from_your_phone_without
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_link_this_app_as_a
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_link_this_app_as_a_2
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_log_in_on_instagram_com
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_log_in_with_a_code
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_same_but_type_a_code
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_scan_a_code_with_telegram
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_sign_in_to_your_workspace
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_sign_in_with_your_matrix
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.presentation.util.toUiText
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceAccounts
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.core.domain.ondevice.DuplicateAccounts
import howyoudoin.feature.networks.presentation.generated.resources.networks_duplicate_account
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceLinkedAccount
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceLoginPrompt
import com.devvalor.howyoudoing.core.domain.ondevice.OnDevicePairing
import com.devvalor.howyoudoing.core.presentation.util.UiText
import com.devvalor.howyoudoing.networks.domain.DisplayType
import com.devvalor.howyoudoing.networks.domain.LoginInputField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.devvalor.howyoudoing.networks.domain.BridgeLoginFlow
import com.devvalor.howyoudoing.networks.domain.BridgeLoginStep
import com.devvalor.howyoudoing.networks.domain.RequiredCookie
import com.devvalor.howyoudoing.networks.domain.BridgeRepository
import com.devvalor.howyoudoing.networks.domain.InputFieldType
import com.devvalor.howyoudoing.networks.presentation.components.DeveloperOptions
import com.devvalor.howyoudoing.networks.presentation.components.phone.Country
import com.devvalor.howyoudoing.networks.presentation.components.phone.DeviceRegionProvider
import com.devvalor.howyoudoing.networks.presentation.components.phone.PhoneNumbers
import com.devvalor.howyoudoing.networks.presentation.navigation.NetworksRoutes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// One screen drives every network's login (server bridge, on-device link, pairing, cookies, CAPTCHA), so the size is
// the sum of those flows; splitting it per flow is the follow-up, not a one-line change.
@Suppress("LargeClass")
class ConnectNetworkViewModel(
    savedStateHandle: SavedStateHandle,
    private val bridgeRepository: BridgeRepository,
    private val deviceRegion: DeviceRegionProvider,
    private val developerOptions: DeveloperOptions,
    private val onDevice: OnDeviceAccounts,
    duplicateAccounts: DuplicateAccounts
) : ViewModel() {

    private var pairingJob: Job? = null

    /** The account being linked (WhatsApp, Telegram or Signal), while it is. */
    private var linkingAccount: OnDeviceLinkedAccount? = null

    private val route = savedStateHandle.toRoute<NetworksRoutes.Connect>()
    private val network = ChatNetwork.valueOf(route.network)


    /** The account being logged in: the network's first, or a second or third one. */
    private val account = AccountRef(network, route.slot)

    /** The client of [account], else the given first-account one (for the networks with a field of their own). */
    private fun <T : OnDeviceMessenger> clientOr(first: T): T {
        @Suppress("UNCHECKED_CAST")
        return (onDevice.forAccount(account) as? T)?.takeIf { it.network == first.network } ?: first
    }

    /** Flow chosen in the tutorial; consumed once so "Try again" shows the chooser. */
    private var preselectedFlowId: String? = route.flowId

    private var hasLoadedInitialData = false
    private var stepJob: Job? = null

    // The bridge URL override is shown once we know there's a bridge server to point at (see loadFlows)
    private val initialState = ConnectNetworkState(network = network)
    private val _state = MutableStateFlow(initialState)
    val state = _state
        .onStart {
            if (!hasLoadedInitialData) {
                loadFlows()
                hasLoadedInitialData = true
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = initialState
        )

    init {
        // The account just logged in was already connected here: its new login was removed
        duplicateAccounts.rejected
            .filter { it.account == AccountRef(network, route.slot) }
            .onEach {
                _state.update { state ->
                    state.copy(
                        isSubmitting = false,
                        error = UiText.Resource(Res.string.networks_duplicate_account, arrayOf(network.displayName))
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun onAction(action: ConnectNetworkAction) {
        when (action) {
            is ConnectNetworkAction.OnBridgeUrlChange -> _state.update { it.copy(bridgeUrl = action.url, error = null) }
            ConnectNetworkAction.OnSaveBridgeUrlClick -> saveBridgeUrl()
            ConnectNetworkAction.OnEditBridgeUrlClick -> {
                stepJob?.cancel()
                _state.update { it.copy(phase = ConnectPhase.ConfigureBridge, error = null) }
            }

            is ConnectNetworkAction.OnFlowClick -> {
                // A new attempt: an error from the last one doesn't apply to it
                _state.update { it.copy(error = null) }
                rejectedCookies = null
                onFlowClick(action.flowId)
            }

            is ConnectNetworkAction.OnInputChange -> onInputChange(action.fieldId, action.value)
            is ConnectNetworkAction.OnCountrySelect -> {
                _state.update { it.copy(phoneCountries = it.phoneCountries + (action.fieldId to action.isoCode)) }
            }

            ConnectNetworkAction.OnSubmitInputClick -> submitInput()
            ConnectNetworkAction.OnSkipInputClick -> skipInput()
            is ConnectNetworkAction.OnCookiesCaptured -> submitCookies(action.cookies)
            ConnectNetworkAction.OnRetryClick -> loadFlows()
            else -> Unit
        }
    }

    private fun onFlowClick(flowId: String) {
        when (flowId) {
            ON_DEVICE_FLOW_ID -> showStepWhenReady {
                when (network) {
                    ChatNetwork.INSTAGRAM -> onDeviceInstagramCookiesStep()
                    ChatNetwork.SLACK -> onDeviceSlackStep()
                    ChatNetwork.BLUESKY -> blueskyLoginStep()
                    ChatNetwork.LINKEDIN -> linkedInStep()
                    ChatNetwork.DISCORD -> discordStep()
                    ChatNetwork.TWITTER -> xStep()
                    ChatNetwork.GOOGLE_VOICE -> googleVoiceStep()
                    else -> onDeviceCookiesStep()
                }
            }

            ON_DEVICE_WHATSAPP_QR_FLOW_ID -> linkAccountOnDevice(clientOr(onDevice.whatsApp), phoneNumber = null)
            ON_DEVICE_WHATSAPP_CODE_FLOW_ID -> showStepWhenReady {
                onDevicePhoneStep(
                    ON_DEVICE_WHATSAPP_CODE_FLOW_ID,
                    ""
                )
            }

            ON_DEVICE_TELEGRAM_FLOW_ID -> showStepWhenReady {
                onDevicePhoneStep(ON_DEVICE_TELEGRAM_FLOW_ID, getString(Res.string.telegram_phone_instructions))
            }

            ON_DEVICE_TELEGRAM_QR_FLOW_ID -> linkAccountOnDevice(clientOr(onDevice.telegram), phoneNumber = null)
            ON_DEVICE_SIGNAL_FLOW_ID -> linkAccountOnDevice(clientOr(onDevice.signal), phoneNumber = null)
            ON_DEVICE_MATRIX_FLOW_ID -> showStepWhenReady { matrixLoginStep() }
            ON_DEVICE_GMESSAGES_QR_FLOW_ID -> googleMessages()?.let { linkAccountOnDevice(it, phoneNumber = null) }
            ON_DEVICE_GMESSAGES_GOOGLE_FLOW_ID -> showStepWhenReady { googleMessagesStep() }
            else -> runStep { bridgeRepository.startLogin(network, flowId) }
        }
    }

    /** On-device steps are built with the user's language strings, which load in a coroutine. */
    private fun showStepWhenReady(build: suspend () -> BridgeLoginStep) {
        stepJob?.cancel()
        stepJob = viewModelScope.launch { showStep(build()) }
    }

    private fun loadFlows() {
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            // Developer builds can point a network at another bridge server; on-device and demo sessions have none
            val canEditBridgeUrl = developerOptions.isEnabled() && bridgeRepository.hasBridgeServer()
            _state.update {
                it.copy(
                    phase = ConnectPhase.Loading,
                    error = null,
                    isSubmitting = false,
                    canEditBridgeUrl = canEditBridgeUrl
                )
            }
            // Networks without a server bridge (Instagram) can only be connected on the device
            if (!network.isSupported) {
                _state.update { it.copy(phase = ConnectPhase.ChooseFlow(onDeviceFlows())) }
                return@launch
            }
            if (!bridgeRepository.observeIsConfigured(network).first()) {
                val savedUrl = bridgeRepository.observeBridgeUrl(network).first().orEmpty()
                _state.update { it.copy(phase = ConnectPhase.ConfigureBridge, bridgeUrl = savedUrl) }
                return@launch
            }
            when (val result = bridgeRepository.getStatus(network)) {
                is Result.Success -> {
                    _state.update { it.copy(phase = ConnectPhase.ChooseFlow(result.data.flows + onDeviceFlows())) }
                    val preselected = preselectedFlowId?.takeIf { id -> result.data.flows.any { it.id == id } }
                    preselectedFlowId = null
                    if (preselected != null) advance(bridgeRepository.startLogin(network, preselected))
                }
                // The on-device option doesn't need the server bridge, so offer it even when that's down; the
                // server's error only matters when there's nothing else (in phone-only mode there's no server at all)
                is Result.Failure -> {
                    val onDeviceOptions = onDeviceFlows()
                    _state.update {
                        it.copy(
                            phase = ConnectPhase.ChooseFlow(onDeviceOptions),
                            error = result.error.toUiText().takeIf { onDeviceOptions.isEmpty() })
                    }
                }
            }
        }
    }

    private fun saveBridgeUrl() {
        val url = state.value.bridgeUrl.trim()
        if (url.isEmpty()) return
        viewModelScope.launch {
            bridgeRepository.setBridgeUrl(network, url)
            loadFlows()
        }
    }

    private fun submitInput() {
        val step = (state.value.phase as? ConnectPhase.Step)?.step as? BridgeLoginStep.UserInput ?: return
        if (!state.value.canSubmitInput) return
        val values = step.fields.associate { it.id to state.value.submittedValue(it) }
        val firstValue = step.fields.firstOrNull()?.let { values[it.id] }
        when (step.loginId) {
            ON_DEVICE_WHATSAPP_CODE_FLOW_ID -> linkAccountOnDevice(clientOr(onDevice.whatsApp), firstValue)
            ON_DEVICE_TELEGRAM_FLOW_ID -> linkAccountOnDevice(clientOr(onDevice.telegram), firstValue)
            ON_DEVICE_PROMPT_FLOW_ID -> submitPromptAnswer(step)
            ON_DEVICE_MATRIX_FLOW_ID -> signInWith(onDevice.matrix, values)
            ON_DEVICE_CREDENTIALS_FLOW_ID -> onDevice.forAccount(account)?.let { signInWith(it, values) }
            ON_DEVICE_X_PASSCODE_FLOW_ID -> onDevice.forAccount(account)?.let { client ->
                signInWith(client, pendingXCookies + ("passcode" to values["passcode"].orEmpty()))
            }
            else -> runStep { bridgeRepository.submitUserInput(network, step, values) }
        }
    }

    private fun skipInput() {
        val step = (state.value.phase as? ConnectPhase.Step)?.step as? BridgeLoginStep.UserInput ?: return
        if (!step.canSkip || state.value.isSubmitting) return
        runStep { bridgeRepository.submitUserInput(network, step, step.fields.associate { it.id to "" }) }
    }

    /** Cookies that were just rejected: the web view finds them again, and they'd only fail again. */
    /** x.com's cookies, kept while the X Chat passcode is asked for. */
    private var pendingXCookies: Map<String, String> = emptyMap()

    private var rejectedCookies: Map<String, String>? = null

    private fun submitCookies(cookiesByName: Map<String, String>) {
        val step = (state.value.phase as? ConnectPhase.Step)?.step as? BridgeLoginStep.Cookies ?: return
        if (state.value.isSubmitting || cookiesByName == rejectedCookies) return
        if (step.loginId == ON_DEVICE_FLOW_ID) return linkOnDevice(step, cookiesByName)
        if (step.loginId == ON_DEVICE_X_FLOW_ID) {
            // X Chat's messages are encrypted: the passcode that unlocks them comes next
            pendingXCookies = step.cookies
                .mapNotNull { cookie -> cookiesByName[cookie.cookieName]?.let { cookie.cookieName to it } }
                .toMap()
            return showStepWhenReady { xPasscodeStep() }
        }
        if (step.loginId == ON_DEVICE_GMESSAGES_GOOGLE_FLOW_ID) {
            val account = googleMessages() ?: return
            val cookies = step.cookies
                .mapNotNull { cookie -> cookiesByName[cookie.cookieName]?.let { cookie.cookieName to it } }
                .toMap()
            return linkAccountOnDevice(account) { account.link(cookies) }
        }
        val values = step.cookies
            .mapNotNull { cookie -> cookiesByName[cookie.cookieName]?.let { cookie.fieldId to it } }
            .toMap()
        runStep { bridgeRepository.submitCookies(network, step, values) }
    }

    /** Runs a request that yields the next login step and moves the UI to it. */
    private fun runStep(request: suspend () -> Result<BridgeLoginStep, DataError.Remote>) {
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            advance(request())
        }
    }

    /** Shows [first] and keeps going through display-and-wait steps until input or completion. */
    private suspend fun advance(first: Result<BridgeLoginStep, DataError.Remote>) {
        var result = first
        while (true) {
            val step = when (val current = result) {
                is Result.Failure -> {
                    _state.update { it.copy(isSubmitting = false, error = current.error.toUiText()) }
                    return
                }

                is Result.Success -> current.data
            }
            showStep(step)
            if (step !is BridgeLoginStep.DisplayAndWait) return

            // The bridge answers once the user scanned / entered the code, or with a refreshed QR.
            result = bridgeRepository.waitForStep(network, step)
            while (result is Result.Failure && result.error == DataError.Remote.REQUEST_TIMEOUT) {
                result = bridgeRepository.waitForStep(network, step)
            }
        }
    }

    /** A pasted "+48 600 …" selects Poland and keeps only the national part in the field. */
    private fun onInputChange(fieldId: String, value: String) {
        val step = (state.value.phase as? ConnectPhase.Step)?.step as? BridgeLoginStep.UserInput
        val isPhone = step?.fields?.firstOrNull { it.id == fieldId }?.type == InputFieldType.PHONE_NUMBER
        val international = if (isPhone) PhoneNumbers.parseInternational(value) else null
        _state.update { state ->
            if (international == null) {
                state.copy(inputValues = state.inputValues + (fieldId to value))
            } else {
                val (country, national) = international
                state.copy(
                    inputValues = state.inputValues + (fieldId to national),
                    phoneCountries = state.phoneCountries + (fieldId to country.isoCode)
                )
            }
        }
    }

    /** SIM / network country where available, e.g. PL for a Polish SIM even if the UI language is en-US. */
    private fun defaultCountry(): Country =
        Country.forIso(deviceRegion.countryIso()) ?: Country.forIso("US")!!

    /** Replaces the login ID with the account's display name ("+48 600…", "Remik Kowalski"). */
    private fun loadConnectedAccountName(loginId: String?) {
        viewModelScope.launch {
            val logins = (bridgeRepository.getStatus(network) as? Result.Success)?.data?.logins.orEmpty()
            val name = logins.firstOrNull { it.id == loginId }?.name ?: logins.singleOrNull()?.name
            if (name != null && name != loginId) {
                _state.update { state ->
                    if (state.phase is ConnectPhase.Connected) {
                        state.copy(phase = ConnectPhase.Connected(name))
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun showStep(step: BridgeLoginStep) {
        if (step is BridgeLoginStep.Complete) loadConnectedAccountName(step.userLoginId)
        _state.update { state ->
            when (step) {
                // The login ID is an internal number (e.g. a Facebook user ID); the name is looked up below
                is BridgeLoginStep.Complete -> state.copy(
                    phase = ConnectPhase.Connected(accountName = null),
                    isSubmitting = false
                )

                is BridgeLoginStep.UserInput -> state.copy(
                    phase = ConnectPhase.Step(step),
                    inputValues = step.fields.mapNotNull { field -> field.defaultValue?.let { field.id to it } }
                        .toMap(),
                    phoneCountries = step.fields
                        .filter { it.type == InputFieldType.PHONE_NUMBER }
                        .associate { it.id to defaultCountry().isoCode },
                    isSubmitting = false
                )

                else -> state.copy(phase = ConnectPhase.Step(step), isSubmitting = false)
            }
        }
    }

    /** The ways to connect [network] from the phone itself; Matrix needs no native client. */
    private suspend fun onDeviceFlows(): List<BridgeLoginFlow> {
        val client = onDevice.forAccount(account) ?: onDevice.forNetwork(network)
        val available = network == ChatNetwork.MATRIX || client?.isSupported == true
        if (!available) return emptyList()
        return ON_DEVICE_OPTIONS[network].orEmpty().map { option ->
            BridgeLoginFlow(
                id = option.flowId,
                name = getString(option.name),
                description = getString(option.description)
            )
        }
    }

    private suspend fun onDevicePhoneStep(flowId: String, instructions: String) = BridgeLoginStep.UserInput(
        loginId = flowId,
        stepId = "on-device-phone",
        instructions = instructions,
        fields = listOf(
            LoginInputField(
                id = "phone",
                name = getString(Res.string.field_phone),
                description = "",
                type = InputFieldType.PHONE_NUMBER
            )
        )
    )

    /**
     * Links an account on this phone: shows the QR codes or pairing code the native client produces, or asks for
     * what it prompts for (Telegram's login code and password), until it's connected.
     */
    private fun linkAccountOnDevice(account: OnDeviceLinkedAccount, phoneNumber: String?) =
        linkAccountOnDevice(account) { account.startLinking(phoneNumber) }

    private fun linkAccountOnDevice(
        account: OnDeviceLinkedAccount,
        start: suspend () -> Result<Unit, DataError.Remote>
    ) {
        stepJob?.cancel()
        pairingJob?.cancel()
        linkingAccount = account
        stepJob = viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            val result = start()
            if (result is Result.Failure) {
                val message = (account.state.value as? OnDeviceMessengerState.Failed)?.message
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        error = message?.let(UiText::DynamicString) ?: result.error.toUiText()
                    )
                }
                return@launch
            }
            pairingJob = viewModelScope.launch {
                launch {
                    account.pairing.collect { pairing ->
                        pairing ?: return@collect
                        showStep(pairingStep(account, pairing))
                    }
                }
                account.state.collect { linkState ->
                    when (linkState) {
                        is OnDeviceMessengerState.Connected -> {
                            _state.update {
                                it.copy(
                                    phase = ConnectPhase.Connected(linkState.accountName),
                                    isSubmitting = false
                                )
                            }
                            linkingAccount = null
                            pairingJob?.cancel()
                        }

                        is OnDeviceMessengerState.Failed -> _state.update {
                            it.copy(isSubmitting = false, error = UiText.DynamicString(linkState.message))
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

    private suspend fun pairingStep(account: OnDeviceLinkedAccount, pairing: OnDevicePairing): BridgeLoginStep {
        val prompt = pairing.prompt
        if (prompt != null) {
            val isCode = prompt.kind == OnDeviceLoginPrompt.Kind.CODE
            val instructions = when {
                isCode && prompt.isRetry -> getString(Res.string.tg_code_retry)
                isCode -> when (prompt.detail) {
                    "app" -> getString(Res.string.tg_code_app)
                    "sms" -> getString(Res.string.tg_code_sms)
                    "email" -> getString(Res.string.tg_code_email)
                    "call" -> getString(Res.string.tg_code_call)
                    "fragment" -> getString(Res.string.tg_code_fragment)
                    else -> getString(Res.string.tg_code_other)
                }

                prompt.isRetry -> getString(Res.string.tg_password_retry)
                else -> prompt.detail?.let { getString(Res.string.tg_password_hint, it) }
                    ?: getString(Res.string.tg_password)
            }
            return BridgeLoginStep.UserInput(
                loginId = ON_DEVICE_PROMPT_FLOW_ID,
                stepId = if (isCode) "on-device-code" else "on-device-password",
                instructions = instructions,
                fields = listOf(
                    if (isCode) LoginInputField(
                        id = "code",
                        name = getString(Res.string.field_login_code),
                        description = "",
                        type = InputFieldType.TWO_FACTOR_CODE
                    )
                    else LoginInputField(
                        id = "password",
                        name = getString(Res.string.field_password),
                        description = "",
                        type = InputFieldType.PASSWORD
                    )
                )
            )
        }
        return BridgeLoginStep.DisplayAndWait(
            loginId = ON_DEVICE_WHATSAPP_QR_FLOW_ID,
            stepId = "on-device-pairing",
            instructions = pairingInstructions(account.network, pairing),
            displayType = when {
                pairing.pairingCode == null -> DisplayType.QR
                // Google Messages pairs by tapping the emoji shown here
                account.network == ChatNetwork.GOOGLE_MESSAGES -> DisplayType.EMOJI
                else -> DisplayType.CODE
            },
            data = pairing.pairingCode ?: pairing.qrCode
        )
    }

    private suspend fun pairingInstructions(network: ChatNetwork, pairing: OnDevicePairing): String =
        when {
            network == ChatNetwork.SIGNAL ->
                getString(Res.string.pair_signal)

            network == ChatNetwork.TELEGRAM ->
                getString(Res.string.pair_telegram)

            network == ChatNetwork.GOOGLE_MESSAGES && pairing.pairingCode != null ->
                getString(Res.string.pair_gmessages_emoji)

            network == ChatNetwork.GOOGLE_MESSAGES ->
                getString(Res.string.pair_gmessages_qr)

            pairing.pairingCode != null ->
                getString(Res.string.pair_whatsapp_code)

            else -> getString(Res.string.pair_whatsapp_qr)
        }

    private fun submitPromptAnswer(step: BridgeLoginStep.UserInput) {
        val account = linkingAccount ?: return
        val value = step.fields.firstOrNull()?.let { state.value.submittedValue(it) } ?: return
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            if (account.submitLinkingInput(value) is Result.Failure) {
                val message = (account.state.value as? OnDeviceMessengerState.Failed)?.message
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        error = message?.let(UiText::DynamicString) ?: UiText.Resource(Res.string.login_failed_retry)
                    )
                }
            } else {
                // The next prompt (or being connected) was announced before the call returned; a repeated wrong
                // answer re-announces the same prompt, which the pairing flow doesn't repeat
                _state.update { it.copy(isSubmitting = false) }
            }
        }
    }

    override fun onCleared() {
        // Leaving the screen mid-linking stops showing codes; an already linked device stays connected.
        // viewModelScope is already cancelled here, so pairingJob only tells whether linking started.
        val account = linkingAccount
        if (pairingJob != null && account != null && account.state.value !is OnDeviceMessengerState.Connected) {
            CoroutineScope(Dispatchers.Default).launch { account.cancelLinking() }
        }
        super.onCleared()
    }

    /** Same cookie web view as the bridge's flow, but the cookies stay on the phone. */
    private suspend fun onDeviceCookiesStep() = BridgeLoginStep.Cookies(
        loginId = ON_DEVICE_FLOW_ID,
        stepId = "on-device-cookies",
        instructions = getString(Res.string.cookies_messenger),
        url = "https://www.messenger.com/",
        // messenger.com only offers its login form to desktop browsers; this matches what hydbridge sends
        userAgent = DESKTOP_USER_AGENT,
        cookies = listOf("xs", "c_user", "datr").map {
            RequiredCookie(
                fieldId = it,
                cookieName = it,
                isRequired = true
            )
        }
    )

    /** Instagram's web session cookies; the optional ones make the session look like a normal browser. */
    private suspend fun onDeviceInstagramCookiesStep() = BridgeLoginStep.Cookies(
        loginId = ON_DEVICE_FLOW_ID,
        stepId = "on-device-instagram-cookies",
        instructions = getString(Res.string.cookies_instagram),
        url = "https://www.instagram.com/accounts/login/",
        userAgent = null,
        cookies = listOf("sessionid", "csrftoken", "ds_user_id").map { RequiredCookie(it, it, isRequired = true) } +
                listOf("mid", "ig_did", "rur").map { RequiredCookie(it, it, isRequired = false) }
    )

    /**
     * Slack's web sign-in. The session is the web client's token (kept in the page's localStorage, read by
     * [SLACK_TOKEN_SCRIPT] like mautrix-slack does) and the "d" cookie.
     */
    private suspend fun onDeviceSlackStep() = BridgeLoginStep.Cookies(
        loginId = ON_DEVICE_FLOW_ID,
        stepId = "on-device-slack",
        instructions = getString(Res.string.cookies_slack),
        url = "https://slack.com/signin",
        // Slack sends phone browsers to its app; the desktop site signs in on the web
        userAgent = DESKTOP_USER_AGENT,
        cookies = listOf("token", "d").map { RequiredCookie(it, it, isRequired = true) },
        extractScript = SLACK_TOKEN_SCRIPT
    )

    private fun OnDeviceMessenger.linkErrorText(): UiText? = linkError?.let(UiText::DynamicString)

    private fun googleMessages(): OnDeviceLinkedAccount? =
        onDevice.forAccount(AccountRef(ChatNetwork.GOOGLE_MESSAGES, account.slot)) as? OnDeviceLinkedAccount

    /** Bluesky's own chat API, signed in with the handle and an app password allowed to read direct messages. */
    private suspend fun blueskyLoginStep() = BridgeLoginStep.UserInput(
        loginId = ON_DEVICE_CREDENTIALS_FLOW_ID,
        stepId = "bluesky-login",
        instructions = getString(Res.string.bluesky_instructions),
        fields = listOf(
            LoginInputField(
                id = "identifier",
                name = getString(Res.string.field_bluesky_handle),
                description = "",
                type = InputFieldType.USERNAME,
                placeholder = "alice.bsky.social"
            ),
            LoginInputField(
                id = "password",
                name = getString(Res.string.field_app_password),
                description = "",
                type = InputFieldType.PASSWORD,
                placeholder = "xxxx-xxxx-xxxx-xxxx"
            )
        )
    )

    /** linkedin.com's session: li_at, and JSESSIONID, which is also the CSRF token (as mautrix-linkedin uses them). */
    private suspend fun linkedInStep() = BridgeLoginStep.Cookies(
        loginId = ON_DEVICE_FLOW_ID,
        stepId = "on-device-linkedin",
        instructions = getString(Res.string.cookies_linkedin),
        url = "https://www.linkedin.com/login",
        userAgent = DESKTOP_USER_AGENT,
        cookies = listOf("li_at", "JSESSIONID").map { RequiredCookie(it, it, isRequired = true) },
        // As mautrix-linkedin: the whole session once the feed is open (after any "confirm it's you" steps), and the
        // login browser's own headers, since LinkedIn ends sessions that come back from a different browser
        extractScript = LINKEDIN_BROWSER_HEADERS_SCRIPT,
        waitForUrl = "^https://www\\.linkedin\\.com/feed",
        keepAllCookies = true
    )

    /** discord.com's sign-in; the session is the web client's token, read from the page by [DISCORD_TOKEN_SCRIPT]. */
    private suspend fun discordStep() = BridgeLoginStep.Cookies(
        loginId = ON_DEVICE_FLOW_ID,
        stepId = "on-device-discord",
        instructions = getString(Res.string.cookies_discord),
        url = "https://discord.com/login",
        // Discord sends phone browsers to its app
        userAgent = DESKTOP_USER_AGENT,
        cookies = listOf(RequiredCookie("token", "token", isRequired = true)),
        extractScript = DISCORD_TOKEN_SCRIPT
    )

    private suspend fun googleVoiceStep() = BridgeLoginStep.Cookies(
        loginId = ON_DEVICE_FLOW_ID,
        stepId = "on-device-googlevoice",
        instructions = getString(Res.string.cookies_gvoice),
        // Signed out, Voice's own pages show a Google Workspace ad instead of a sign-in
        url = "https://accounts.google.com/AccountChooser?continue=https://voice.google.com/u/0/messages",
        userAgent = DESKTOP_USER_AGENT,
        cookies = GOOGLE_COOKIES.map { RequiredCookie(it, it, isRequired = true) } +
            listOf("OSID", "__Secure-1PSIDTS", "COMPASS").map { RequiredCookie(it, it, isRequired = false) }
    )

    /** x.com's session (auth_token and ct0, as mautrix-twitter uses them); the X Chat passcode is asked next. */
    private suspend fun xStep() = BridgeLoginStep.Cookies(
        loginId = ON_DEVICE_X_FLOW_ID,
        stepId = "on-device-x",
        instructions = "",
        url = "https://x.com/i/flow/login",
        userAgent = DESKTOP_USER_AGENT,
        cookies = listOf("auth_token", "ct0").map { RequiredCookie(it, it, isRequired = true) } +
            RequiredCookie("twid", "twid", isRequired = false)
    )

    private suspend fun xPasscodeStep() = BridgeLoginStep.UserInput(
        loginId = ON_DEVICE_X_PASSCODE_FLOW_ID,
        stepId = "on-device-x-passcode",
        instructions = getString(Res.string.x_passcode_instructions),
        fields = listOf(
            LoginInputField(
                id = "passcode",
                name = getString(Res.string.field_x_passcode),
                description = "",
                type = InputFieldType.PASSWORD
            )
        )
    )

    /** Pairing Google Messages through the Google account: messages.google.com's cookies, then an emoji. */
    private suspend fun googleMessagesStep() = BridgeLoginStep.Cookies(
        loginId = ON_DEVICE_GMESSAGES_GOOGLE_FLOW_ID,
        stepId = "on-device-gmessages-google",
        instructions = getString(Res.string.cookies_gmessages),
        // Signing in first: the config page itself only shows raw data while signed out
        url = "https://accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config",
        userAgent = DESKTOP_USER_AGENT,
        cookies = (GOOGLE_COOKIES + "OSID").map { RequiredCookie(it, it, isRequired = true) } +
            RequiredCookie("__Secure-1PSIDTS", "__Secure-1PSIDTS", isRequired = false)
    )

    private suspend fun matrixLoginStep() = BridgeLoginStep.UserInput(
        loginId = ON_DEVICE_MATRIX_FLOW_ID,
        stepId = "matrix-login",
        instructions = getString(Res.string.matrix_instructions),
        fields = listOf(
            LoginInputField(
                id = "homeserver",
                name = getString(Res.string.field_homeserver),
                description = "",
                type = InputFieldType.DOMAIN,
                defaultValue = "matrix.org",
                placeholder = "matrix.org"
            ),
            LoginInputField(
                id = "username",
                name = getString(Res.string.field_matrix_user),
                description = "",
                type = InputFieldType.USERNAME,
                placeholder = "@alice:matrix.org"
            ),
            LoginInputField(
                id = "password",
                name = getString(Res.string.field_password),
                description = "",
                type = InputFieldType.PASSWORD
            )
        )
    )

    /** Signs in with typed values (Matrix: homeserver, username and password; Bluesky: handle and app password). */
    private fun signInWith(client: OnDeviceMessenger, values: Map<String, String>) {
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            when (val result = client.link(values)) {
                is Result.Success -> {
                    val name = (client.state.value as? OnDeviceMessengerState.Connected)?.accountName
                    _state.update { it.copy(phase = ConnectPhase.Connected(name), isSubmitting = false) }
                }

                is Result.Failure -> _state.update {
                    it.copy(isSubmitting = false, error = client.linkErrorText() ?: result.error.toUiText())
                }
            }
        }
    }

    private fun linkOnDevice(step: BridgeLoginStep.Cookies, cookiesByName: Map<String, String>) {
        val cookies = if (step.keepAllCookies) {
            cookiesByName
        } else {
            step.cookies.mapNotNull { cookie -> cookiesByName[cookie.cookieName]?.let { cookie.cookieName to it } }
                .toMap()
        }
        val client = onDevice.forAccount(account) ?: onDevice.messenger
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            when (val result = client.link(cookies)) {
                is Result.Success -> {
                    val name = (client.state.value as? OnDeviceMessengerState.Connected)?.accountName
                    _state.update { it.copy(phase = ConnectPhase.Connected(name), isSubmitting = false) }
                }

                is Result.Failure -> {
                    rejectedCookies = cookiesByName
                    _state.update {
                        it.copy(isSubmitting = false, error = client.linkErrorText() ?: result.error.toUiText())
                    }
                }
            }
        }
    }

    private companion object {
        const val ON_DEVICE_FLOW_ID = "howyoudoin-on-device"
        const val ON_DEVICE_WHATSAPP_QR_FLOW_ID = "howyoudoin-on-device-whatsapp-qr"
        const val ON_DEVICE_WHATSAPP_CODE_FLOW_ID = "howyoudoin-on-device-whatsapp-code"
        const val ON_DEVICE_TELEGRAM_FLOW_ID = "howyoudoin-on-device-telegram"
        const val ON_DEVICE_TELEGRAM_QR_FLOW_ID = "howyoudoin-on-device-telegram-qr"
        const val ON_DEVICE_SIGNAL_FLOW_ID = "howyoudoin-on-device-signal"
        const val ON_DEVICE_MATRIX_FLOW_ID = "howyoudoin-matrix-account"
        const val ON_DEVICE_GMESSAGES_QR_FLOW_ID = "howyoudoin-on-device-gmessages-qr"
        const val ON_DEVICE_GMESSAGES_GOOGLE_FLOW_ID = "howyoudoin-on-device-gmessages-google"

        const val ON_DEVICE_X_FLOW_ID = "howyoudoin-on-device-x"
        const val ON_DEVICE_X_PASSCODE_FLOW_ID = "howyoudoin-on-device-x-passcode"

        /** Signing in with typed values on a network other than Matrix (Bluesky). */
        const val ON_DEVICE_CREDENTIALS_FLOW_ID = "howyoudoin-on-device-credentials"

        /** Google's session cookies that every Google service needs (as mautrix-gmessages and -gvoice ask for). */
        val GOOGLE_COOKIES = listOf("SID", "HSID", "SSID", "APISID", "SAPISID")

        /** Answers to what a linking client asks for (Telegram's login code and password). */
        const val ON_DEVICE_PROMPT_FLOW_ID = "howyoudoin-on-device-prompt"

        /** The ways to connect each network from the phone itself. */
        private val ON_DEVICE_OPTIONS: Map<ChatNetwork, List<OnDeviceOption>> = mapOf(
            ChatNetwork.MESSENGER to listOf(
                OnDeviceOption(
                    ON_DEVICE_FLOW_ID,
                    Res.string.flow_device,
                    Res.string.connectnetwork_connect_from_your_phone_without
                )
            ),
            ChatNetwork.INSTAGRAM to listOf(
                OnDeviceOption(
                    ON_DEVICE_FLOW_ID,
                    Res.string.flow_device,
                    Res.string.connectnetwork_log_in_on_instagram_com
                )
            ),
            ChatNetwork.TELEGRAM to listOf(
                OnDeviceOption(
                    ON_DEVICE_TELEGRAM_FLOW_ID,
                    Res.string.flow_device_phone,
                    Res.string.connectnetwork_log_in_with_a_code
                ),
                OnDeviceOption(
                    ON_DEVICE_TELEGRAM_QR_FLOW_ID,
                    Res.string.flow_device_qr,
                    Res.string.connectnetwork_scan_a_code_with_telegram
                )
            ),
            ChatNetwork.SIGNAL to listOf(
                OnDeviceOption(
                    ON_DEVICE_SIGNAL_FLOW_ID,
                    Res.string.flow_device,
                    Res.string.connectnetwork_link_this_app_as_a
                )
            ),
            ChatNetwork.WHATSAPP to listOf(
                OnDeviceOption(
                    ON_DEVICE_WHATSAPP_QR_FLOW_ID,
                    Res.string.flow_device_qr,
                    Res.string.connectnetwork_link_this_app_as_a_2
                ),
                OnDeviceOption(
                    ON_DEVICE_WHATSAPP_CODE_FLOW_ID,
                    Res.string.flow_device_code,
                    Res.string.connectnetwork_same_but_type_a_code
                )
            ),
            ChatNetwork.MATRIX to listOf(
                OnDeviceOption(
                    ON_DEVICE_MATRIX_FLOW_ID,
                    Res.string.flow_matrix,
                    Res.string.connectnetwork_sign_in_with_your_matrix
                )
            ),
            ChatNetwork.SLACK to listOf(
                OnDeviceOption(
                    ON_DEVICE_FLOW_ID,
                    Res.string.flow_device,
                    Res.string.connectnetwork_sign_in_to_your_workspace
                )
            ),
            ChatNetwork.GOOGLE_MESSAGES to listOf(
                OnDeviceOption(
                    ON_DEVICE_GMESSAGES_GOOGLE_FLOW_ID,
                    Res.string.flow_google_account,
                    Res.string.connectnetwork_gmessages_google
                ),
                OnDeviceOption(
                    ON_DEVICE_GMESSAGES_QR_FLOW_ID,
                    Res.string.flow_device_qr,
                    Res.string.connectnetwork_gmessages_qr
                )
            ),
            ChatNetwork.BLUESKY to listOf(
                OnDeviceOption(ON_DEVICE_FLOW_ID, Res.string.flow_device, Res.string.connectnetwork_bluesky)
            ),
            ChatNetwork.LINKEDIN to listOf(
                OnDeviceOption(ON_DEVICE_FLOW_ID, Res.string.flow_device, Res.string.connectnetwork_linkedin)
            ),
            ChatNetwork.DISCORD to listOf(
                OnDeviceOption(ON_DEVICE_FLOW_ID, Res.string.flow_device, Res.string.connectnetwork_discord)
            ),
            ChatNetwork.TWITTER to listOf(
                OnDeviceOption(ON_DEVICE_FLOW_ID, Res.string.flow_device, Res.string.connectnetwork_x)
            ),
            ChatNetwork.GOOGLE_VOICE to listOf(
                OnDeviceOption(ON_DEVICE_FLOW_ID, Res.string.flow_device, Res.string.connectnetwork_gvoice)
            )
        )
    }
}

/** The desktop Chrome user agent hydbridge (messagix) uses, so the session looks the same from both. */
/** The login browser's User-Agent and client hints, as the "hyd.header.<name>" values the LinkedIn client sends. */
private const val LINKEDIN_BROWSER_HEADERS_SCRIPT = """
(function() {
  var d = navigator.userAgentData, h = { 'hyd.header.User-Agent': navigator.userAgent };
  if (d) {
    h['hyd.header.Sec-Ch-Ua'] = d.brands.map(function(b) { return '"' + b.brand + '";v="' + b.version + '"'; }).join(', ');
    h['hyd.header.Sec-Ch-Ua-Platform'] = '"' + d.platform + '"';
    h['hyd.header.Sec-Ch-Ua-Mobile'] = d.mobile ? '?1' : '?0';
  }
  return JSON.stringify(h);
})()
"""

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

/**
 * Finds the Slack web client's token in localStorage (and clicks "use Slack in your browser" when Slack offers
 * the app instead); returns {"token": "xoxc-…"} once signed in.
 */
private const val SLACK_TOKEN_SCRIPT = """
(function() {
  try {
    if (/\.slack\.com$/.test(location.host) && !window.__hydSlackRedirected) {
      var link = document.querySelector('.p-ssb_redirect__body .c-link');
      if (link) { window.__hydSlackRedirected = true; location.href = link.getAttribute('href'); }
    }
    var config = localStorage.getItem('localConfig_v2');
    if (!config || config.indexOf('xoxc-') < 0) return '{}';
    var teams = JSON.parse(config).teams;
    var token = teams[Object.keys(teams)[0]].token;
    return JSON.stringify({ token: token });
  } catch (e) { return '{}'; }
})()
"""

/**
 * Finds the Discord web client's token once signed in. Discord removes localStorage from its own page, so it's read
 * through a fresh iframe's (the same origin's storage); returns {"token": "…"}.
 */
private const val DISCORD_TOKEN_SCRIPT = """
(function() {
  try {
    if (!/(^|\.)discord\.com$/.test(location.host)) return '{}';
    var frame = window.__hydDiscordFrame;
    if (!frame) {
      frame = document.createElement('iframe');
      frame.style.display = 'none';
      document.body.appendChild(frame);
      window.__hydDiscordFrame = frame;
    }
    var token = frame.contentWindow.localStorage.getItem('token');
    if (!token) return '{}';
    return JSON.stringify({ token: JSON.parse(token) });
  } catch (e) { return '{}'; }
})()
"""

private class OnDeviceOption(val flowId: String, val name: StringResource, val description: StringResource)
