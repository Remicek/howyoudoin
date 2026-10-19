package com.devvalor.howyoudoing.networks.presentation.connect

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.presentation.util.toUiText
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceInstagram
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceLinkedAccount
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceLoginPrompt
import com.devvalor.howyoudoing.core.domain.ondevice.OnDevicePairing
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceSignal
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceTelegram
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

class ConnectNetworkViewModel(
    savedStateHandle: SavedStateHandle,
    private val bridgeRepository: BridgeRepository,
    private val deviceRegion: DeviceRegionProvider,
    developerOptions: DeveloperOptions,
    private val onDeviceMessenger: OnDeviceMessenger,
    private val onDeviceWhatsApp: OnDeviceWhatsApp,
    private val onDeviceInstagram: OnDeviceInstagram,
    private val onDeviceTelegram: OnDeviceTelegram,
    private val onDeviceSignal: OnDeviceSignal
) : ViewModel() {

    private var pairingJob: Job? = null
    /** The account being linked (WhatsApp, Telegram or Signal), while it is. */
    private var linkingAccount: OnDeviceLinkedAccount? = null

    private val route = savedStateHandle.toRoute<NetworksRoutes.Connect>()
    private val network = ChatNetwork.valueOf(route.network)

    /** Flow chosen in the tutorial; consumed once so "Try again" shows the chooser. */
    private var preselectedFlowId: String? = route.flowId

    private var hasLoadedInitialData = false
    private var stepJob: Job? = null

    private val initialState = ConnectNetworkState(network = network, canEditBridgeUrl = developerOptions.isEnabled())
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

    fun onAction(action: ConnectNetworkAction) {
        when (action) {
            is ConnectNetworkAction.OnBridgeUrlChange -> _state.update { it.copy(bridgeUrl = action.url, error = null) }
            ConnectNetworkAction.OnSaveBridgeUrlClick -> saveBridgeUrl()
            ConnectNetworkAction.OnEditBridgeUrlClick -> {
                stepJob?.cancel()
                _state.update { it.copy(phase = ConnectPhase.ConfigureBridge, error = null) }
            }
            is ConnectNetworkAction.OnFlowClick -> when (action.flowId) {
                ON_DEVICE_FLOW_ID -> showStep(if (network == ChatNetwork.INSTAGRAM) onDeviceInstagramCookiesStep() else onDeviceCookiesStep())
                ON_DEVICE_WHATSAPP_QR_FLOW_ID -> linkAccountOnDevice(onDeviceWhatsApp, phoneNumber = null)
                ON_DEVICE_WHATSAPP_CODE_FLOW_ID -> showStep(onDevicePhoneStep(ON_DEVICE_WHATSAPP_CODE_FLOW_ID, ""))
                ON_DEVICE_TELEGRAM_FLOW_ID -> showStep(
                    onDevicePhoneStep(ON_DEVICE_TELEGRAM_FLOW_ID, "Enter the phone number of your Telegram account. Telegram sends you a login code.")
                )
                ON_DEVICE_SIGNAL_FLOW_ID -> linkAccountOnDevice(onDeviceSignal, phoneNumber = null)
                else -> runStep { bridgeRepository.startLogin(network, action.flowId) }
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

    private fun loadFlows() {
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            _state.update { it.copy(phase = ConnectPhase.Loading, error = null, isSubmitting = false) }
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
                // The on-device option doesn't need the server bridge, so offer it even when that's down
                is Result.Failure -> _state.update {
                    it.copy(phase = ConnectPhase.ChooseFlow(onDeviceFlows()), error = result.error.toUiText())
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
        when (step.loginId) {
            ON_DEVICE_WHATSAPP_CODE_FLOW_ID ->
                return linkAccountOnDevice(onDeviceWhatsApp, step.fields.firstOrNull()?.let { state.value.submittedValue(it) })
            ON_DEVICE_TELEGRAM_FLOW_ID ->
                return linkAccountOnDevice(onDeviceTelegram, step.fields.firstOrNull()?.let { state.value.submittedValue(it) })
            ON_DEVICE_PROMPT_FLOW_ID -> return submitPromptAnswer(step)
        }
        val values = step.fields.associate { it.id to state.value.submittedValue(it) }
        runStep { bridgeRepository.submitUserInput(network, step, values) }
    }

    private fun skipInput() {
        val step = (state.value.phase as? ConnectPhase.Step)?.step as? BridgeLoginStep.UserInput ?: return
        if (!step.canSkip || state.value.isSubmitting) return
        runStep { bridgeRepository.submitUserInput(network, step, step.fields.associate { it.id to "" }) }
    }

    private fun submitCookies(cookiesByName: Map<String, String>) {
        val step = (state.value.phase as? ConnectPhase.Step)?.step as? BridgeLoginStep.Cookies ?: return
        if (state.value.isSubmitting) return
        if (step.loginId == ON_DEVICE_FLOW_ID) return linkOnDevice(step, cookiesByName)
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
                    if (state.phase is ConnectPhase.Connected) state.copy(phase = ConnectPhase.Connected(name)) else state
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
                    inputValues = step.fields.mapNotNull { field -> field.defaultValue?.let { field.id to it } }.toMap(),
                    phoneCountries = step.fields
                        .filter { it.type == InputFieldType.PHONE_NUMBER }
                        .associate { it.id to defaultCountry().isoCode },
                    isSubmitting = false
                )
                else -> state.copy(phase = ConnectPhase.Step(step), isSubmitting = false)
            }
        }
    }

    private fun onDeviceFlows(): List<BridgeLoginFlow> = when {
        network == ChatNetwork.MESSENGER && onDeviceMessenger.isSupported -> listOf(
            BridgeLoginFlow(
                id = ON_DEVICE_FLOW_ID,
                name = "On this device",
                description = "Connect from your phone, without the server bridge."
            )
        )
        network == ChatNetwork.INSTAGRAM && onDeviceInstagram.isSupported -> listOf(
            BridgeLoginFlow(
                id = ON_DEVICE_FLOW_ID,
                name = "On this device",
                description = "Log in on instagram.com; your direct messages connect from this phone."
            )
        )
        network == ChatNetwork.TELEGRAM && onDeviceTelegram.isSupported -> listOf(
            BridgeLoginFlow(
                id = ON_DEVICE_TELEGRAM_FLOW_ID,
                name = "On this device",
                description = "Log in with your phone number; chats connect from this phone."
            )
        )
        network == ChatNetwork.SIGNAL && onDeviceSignal.isSupported -> listOf(
            BridgeLoginFlow(
                id = ON_DEVICE_SIGNAL_FLOW_ID,
                name = "On this device",
                description = "Link this app as a Signal device by scanning a QR code."
            )
        )
        network == ChatNetwork.WHATSAPP && onDeviceWhatsApp.isSupported -> listOf(
            BridgeLoginFlow(
                id = ON_DEVICE_WHATSAPP_QR_FLOW_ID,
                name = "On this device · QR code",
                description = "Link this app as a WhatsApp device directly, without the server bridge."
            ),
            BridgeLoginFlow(
                id = ON_DEVICE_WHATSAPP_CODE_FLOW_ID,
                name = "On this device · pairing code",
                description = "Same, but type a code on your phone instead of scanning."
            )
        )
        else -> emptyList()
    }

    private fun onDevicePhoneStep(flowId: String, instructions: String) = BridgeLoginStep.UserInput(
        loginId = flowId,
        stepId = "on-device-phone",
        instructions = instructions,
        fields = listOf(LoginInputField(id = "phone", name = "Phone number", description = "", type = InputFieldType.PHONE_NUMBER))
    )

    /**
     * Links an account on this phone: shows the QR codes or pairing code the native client produces, or asks for
     * what it prompts for (Telegram's login code and password), until it's connected.
     */
    private fun linkAccountOnDevice(account: OnDeviceLinkedAccount, phoneNumber: String?) {
        stepJob?.cancel()
        pairingJob?.cancel()
        linkingAccount = account
        stepJob = viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            val result = account.startLinking(phoneNumber)
            if (result is Result.Failure) {
                val message = (account.state.value as? OnDeviceMessengerState.Failed)?.message
                _state.update { it.copy(isSubmitting = false, error = message?.let(UiText::DynamicString) ?: result.error.toUiText()) }
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
                            _state.update { it.copy(phase = ConnectPhase.Connected(linkState.accountName), isSubmitting = false) }
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

    private fun pairingStep(account: OnDeviceLinkedAccount, pairing: OnDevicePairing): BridgeLoginStep {
        val prompt = pairing.prompt
        if (prompt != null) {
            val isCode = prompt.kind == OnDeviceLoginPrompt.Kind.CODE
            val instructions = when {
                isCode && prompt.isRetry -> "That code didn't work. Check it and try again."
                isCode -> when (prompt.detail) {
                    "app" -> "Telegram sent a login code to your Telegram app (in the chat from \"Telegram\")."
                    "sms" -> "Telegram sent a login code by SMS."
                    "email" -> "Telegram sent a login code to your email."
                    else -> "Enter the login code Telegram sent you."
                }
                prompt.isRetry -> "Wrong password, try again."
                else -> "Your account has two-step verification. Enter its password" +
                    (prompt.detail?.let { " (hint: $it)" } ?: "") + "."
            }
            return BridgeLoginStep.UserInput(
                loginId = ON_DEVICE_PROMPT_FLOW_ID,
                stepId = if (isCode) "on-device-code" else "on-device-password",
                instructions = instructions,
                fields = listOf(
                    if (isCode) LoginInputField(id = "code", name = "Login code", description = "", type = InputFieldType.TWO_FACTOR_CODE)
                    else LoginInputField(id = "password", name = "Password", description = "", type = InputFieldType.PASSWORD)
                )
            )
        }
        val instructions = when {
            account.network == ChatNetwork.SIGNAL ->
                "On your phone open Signal → Settings → Linked devices → Link new device, and scan this code."
            pairing.pairingCode != null ->
                "On your phone open WhatsApp → Linked devices → Link a device → Link with phone number instead, and enter this code."
            else -> "On your phone open WhatsApp → Linked devices → Link a device, and scan this code."
        }
        return BridgeLoginStep.DisplayAndWait(
            loginId = ON_DEVICE_WHATSAPP_QR_FLOW_ID,
            stepId = "on-device-pairing",
            instructions = instructions,
            displayType = if (pairing.pairingCode != null) DisplayType.CODE else DisplayType.QR,
            data = pairing.pairingCode ?: pairing.qrCode
        )
    }

    private fun submitPromptAnswer(step: BridgeLoginStep.UserInput) {
        val account = linkingAccount ?: return
        val value = step.fields.firstOrNull()?.let { state.value.submittedValue(it) } ?: return
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            if (account.submitLinkingInput(value) is Result.Failure) {
                val message = (account.state.value as? OnDeviceMessengerState.Failed)?.message ?: "Couldn't log in, try again"
                _state.update { it.copy(isSubmitting = false, error = UiText.DynamicString(message)) }
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
    private fun onDeviceCookiesStep() = BridgeLoginStep.Cookies(
        loginId = ON_DEVICE_FLOW_ID,
        stepId = "on-device-cookies",
        instructions = "Log in to Messenger. HowYouDoin then connects from this phone directly.",
        url = "https://www.messenger.com/",
        // messenger.com only offers its login form to desktop browsers; this matches what hydbridge sends
        userAgent = DESKTOP_USER_AGENT,
        cookies = listOf("xs", "c_user", "datr").map { RequiredCookie(fieldId = it, cookieName = it, isRequired = true) }
    )

    /** Instagram's web session cookies; the optional ones make the session look like a normal browser. */
    private fun onDeviceInstagramCookiesStep() = BridgeLoginStep.Cookies(
        loginId = ON_DEVICE_FLOW_ID,
        stepId = "on-device-instagram-cookies",
        instructions = "Log in to Instagram. HowYouDoin then connects your direct messages from this phone.",
        url = "https://www.instagram.com/accounts/login/",
        userAgent = null,
        cookies = listOf("sessionid", "csrftoken", "ds_user_id").map { RequiredCookie(it, it, isRequired = true) } +
            listOf("mid", "ig_did", "rur").map { RequiredCookie(it, it, isRequired = false) }
    )

    private fun linkOnDevice(step: BridgeLoginStep.Cookies, cookiesByName: Map<String, String>) {
        val cookies = step.cookies.mapNotNull { cookie -> cookiesByName[cookie.cookieName]?.let { cookie.cookieName to it } }.toMap()
        val client = if (network == ChatNetwork.INSTAGRAM) onDeviceInstagram else onDeviceMessenger
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            when (val result = client.link(cookies)) {
                is Result.Success -> {
                    val name = (client.state.value as? OnDeviceMessengerState.Connected)?.accountName
                    _state.update { it.copy(phase = ConnectPhase.Connected(name), isSubmitting = false) }
                }
                is Result.Failure -> _state.update { it.copy(isSubmitting = false, error = result.error.toUiText()) }
            }
        }
    }

    private companion object {
        const val ON_DEVICE_FLOW_ID = "howyoudoin-on-device"
        const val ON_DEVICE_WHATSAPP_QR_FLOW_ID = "howyoudoin-on-device-whatsapp-qr"
        const val ON_DEVICE_WHATSAPP_CODE_FLOW_ID = "howyoudoin-on-device-whatsapp-code"
        const val ON_DEVICE_TELEGRAM_FLOW_ID = "howyoudoin-on-device-telegram"
        const val ON_DEVICE_SIGNAL_FLOW_ID = "howyoudoin-on-device-signal"
        /** Answers to what a linking client asks for (Telegram's login code and password). */
        const val ON_DEVICE_PROMPT_FLOW_ID = "howyoudoin-on-device-prompt"
    }
}

/** The desktop Chrome user agent hydbridge (messagix) uses, so the session looks the same from both. */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
