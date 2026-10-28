package com.devvalor.howyoudoing.networks.presentation.connect

import howyoudoin.feature.networks.presentation.generated.resources.connect_title
import howyoudoin.feature.networks.presentation.generated.resources.connect_bridge_intro
import howyoudoin.feature.networks.presentation.generated.resources.connect_unofficial_telegram
import howyoudoin.feature.networks.presentation.generated.resources.connect_matrix_open
import howyoudoin.feature.networks.presentation.generated.resources.connect_unofficial
import howyoudoin.feature.networks.presentation.generated.resources.connect_phone_intro
import howyoudoin.feature.networks.presentation.generated.resources.connect_restore_history
import howyoudoin.feature.networks.presentation.generated.resources.connect_continue
import howyoudoin.feature.networks.presentation.generated.resources.connect_connected
import howyoudoin.feature.networks.presentation.generated.resources.connect_logged_in_as
import howyoudoin.feature.networks.presentation.generated.resources.connect_chats_soon
import howyoudoin.feature.networks.presentation.generated.resources.connect_messenger_tip
import howyoudoin.feature.networks.presentation.generated.resources.cookies_steps_slack
import howyoudoin.feature.networks.presentation.generated.resources.cookies_steps
import howyoudoin.feature.networks.presentation.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_bridge_address
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_bridge_settings
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_bridge_url
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_continue
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_done
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_enter_the_6_digit_pin
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_how_do_you_want_to
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_in_whatsapp_open_settings_linked
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_link_with_your_phone_number
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_messenger_allows_only_a_limited
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_restore_encrypted_chats
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_skip_for_now
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_try_again
import howyoudoin.feature.networks.presentation.generated.resources.connectnetwork_waiting_for_confirmation
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_back
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_recommended
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.core.designsystem.components.NetworkBadge
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.networks.domain.BridgeLoginFlow
import com.devvalor.howyoudoing.networks.domain.BridgeLoginStep
import com.devvalor.howyoudoing.networks.domain.DisplayType
import com.devvalor.howyoudoing.networks.domain.InputFieldType
import com.devvalor.howyoudoing.networks.domain.LoginInputField
import com.devvalor.howyoudoing.networks.presentation.components.CookieCaptureWebView
import com.devvalor.howyoudoing.networks.presentation.components.PairingCode
import com.devvalor.howyoudoing.networks.presentation.components.QrCode
import com.devvalor.howyoudoing.networks.presentation.components.phone.PhoneNumberInput
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ConnectNetworkRoot(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: ConnectNetworkViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ConnectNetworkScreen(
        state = state,
        onAction = { action ->
            when (action) {
                ConnectNetworkAction.OnDoneClick -> onDone()
                ConnectNetworkAction.OnBackClick -> onBack()
                else -> viewModel.onAction(action)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectNetworkScreen(
    state: ConnectNetworkState,
    onAction: (ConnectNetworkAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NetworkBadge(network = state.network, size = 28.dp, borderColor = Color.Transparent)
                        Spacer(Modifier.width(HowYouDoinSpacing.sm))
                        Text(stringResource(Res.string.connect_title, state.network.displayName))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onAction(ConnectNetworkAction.OnBackClick) }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(Res.string.tutorial_back)
                        )
                    }
                },
                actions = {
                    // A Matrix account has no bridge to configure
                    val canChooseServer = state.phase is ConnectPhase.ChooseFlow && state.canEditBridgeUrl
                    if (canChooseServer && state.network != ChatNetwork.MATRIX) {
                        IconButton(onClick = { onAction(ConnectNetworkAction.OnEditBridgeUrlClick) }) {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = stringResource(Res.string.connectnetwork_bridge_settings)
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (state.isSubmitting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val phase = state.phase
            if (phase is ConnectPhase.Step && phase.step is BridgeLoginStep.Cookies) {
                // The web view needs the full height and handles its own scrolling.
                CookiesStep(
                    step = phase.step,
                    network = state.network,
                    isSubmitting = state.isSubmitting,
                    error = state.error?.asString(),
                    onAction = onAction
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(HowYouDoinSpacing.containerPadding),
                ) {
                    when (phase) {
                        ConnectPhase.Loading -> Box(
                            Modifier.fillMaxWidth().padding(top = HowYouDoinSpacing.xl),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }

                        ConnectPhase.ConfigureBridge -> ConfigureBridge(state = state, onAction = onAction)
                        is ConnectPhase.ChooseFlow -> ChooseFlow(
                            network = state.network,
                            flows = phase.flows,
                            onAction = onAction
                        )

                        is ConnectPhase.Step -> when (val step = phase.step) {
                            is BridgeLoginStep.DisplayAndWait -> DisplayStep(step, state.network)
                            is BridgeLoginStep.UserInput -> UserInputStep(
                                step = step,
                                state = state,
                                onAction = onAction
                            )

                            is BridgeLoginStep.Cookies, is BridgeLoginStep.Complete -> Unit
                        }

                        is ConnectPhase.Connected -> Connected(
                            state = state,
                            accountName = phase.accountName,
                            onAction = onAction
                        )
                    }

                    state.error?.let { error ->
                        Spacer(Modifier.height(HowYouDoinSpacing.md))
                        Text(
                            error.asString(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(HowYouDoinSpacing.sm))
                        OutlinedButton(onClick = { onAction(ConnectNetworkAction.OnRetryClick) }) {
                            Text(stringResource(Res.string.connectnetwork_try_again))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigureBridge(state: ConnectNetworkState, onAction: (ConnectNetworkAction) -> Unit) {
    Text(
        stringResource(Res.string.connectnetwork_bridge_address),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(HowYouDoinSpacing.sm))
    Text(
        stringResource(Res.string.connect_bridge_intro, state.network.displayName),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(HowYouDoinSpacing.md))
    OutlinedTextField(
        value = state.bridgeUrl,
        onValueChange = { onAction(ConnectNetworkAction.OnBridgeUrlChange(it)) },
        label = { Text(stringResource(Res.string.connectnetwork_bridge_url)) },
        placeholder = { Text("https://matrix.example.com/${state.network.name.lowercase()}") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(HowYouDoinSpacing.md))
    Button(
        onClick = { onAction(ConnectNetworkAction.OnSaveBridgeUrlClick) },
        enabled = state.bridgeUrl.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.connectnetwork_continue))
    }
}

@Composable
private fun ChooseFlow(network: ChatNetwork, flows: List<BridgeLoginFlow>, onAction: (ConnectNetworkAction) -> Unit) {
    if (flows.isEmpty()) return
    Text(
        stringResource(Res.string.connectnetwork_how_do_you_want_to),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(HowYouDoinSpacing.md))
    Column(verticalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm)) {
        LoginFlowLabels.present(network, flows).forEach { label ->
            Card(
                onClick = { onAction(ConnectNetworkAction.OnFlowClick(label.flowId)) },
                colors = CardDefaults.cardColors(
                    containerColor = if (label.isRecommended) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(HowYouDoinSpacing.md)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label.title.asString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (label.isRecommended) {
                                Spacer(Modifier.width(HowYouDoinSpacing.sm))
                                Text(
                                    stringResource(Res.string.tutorial_recommended),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        val description = label.description.asString()
                        if (description.isNotBlank()) {
                            Text(
                                description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
    Spacer(Modifier.height(HowYouDoinSpacing.lg))
    Text(
        if (network == ChatNetwork.TELEGRAM) {
            stringResource(Res.string.connect_unofficial_telegram)
        } else if (network == ChatNetwork.MATRIX) {
            stringResource(Res.string.connect_matrix_open)
        } else {
            stringResource(Res.string.connect_unofficial, network.displayName)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DisplayStep(step: BridgeLoginStep.DisplayAndWait, network: ChatNetwork) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Instructions(step.instructions)
        Spacer(Modifier.height(HowYouDoinSpacing.lg))
        val data = step.data
        when {
            data == null -> Unit
            step.displayType == DisplayType.QR -> QrCode(data = data)
            step.displayType == DisplayType.CODE -> {
                PairingCode(code = data)
                if (network == ChatNetwork.WHATSAPP) {
                    Spacer(Modifier.height(HowYouDoinSpacing.lg))
                    Text(
                        stringResource(Res.string.connectnetwork_in_whatsapp_open_settings_linked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> Text(
                data,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = if (step.displayType == DisplayType.EMOJI) 48.sp else 36.sp,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
                    .padding(horizontal = HowYouDoinSpacing.lg, vertical = HowYouDoinSpacing.md),
            )
        }
        Spacer(Modifier.height(HowYouDoinSpacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(HowYouDoinSpacing.sm))
            Text(
                stringResource(Res.string.connectnetwork_waiting_for_confirmation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UserInputStep(
    step: BridgeLoginStep.UserInput,
    state: ConnectNetworkState,
    onAction: (ConnectNetworkAction) -> Unit,
) {
    val asksForPhone = step.fields.any { it.type == InputFieldType.PHONE_NUMBER }
    if (step.isMessengerHistoryPin) {
        HistoryPinHeader(step.instructions)
    } else if (step.instructions.isBlank() && asksForPhone) {
        Text(
            stringResource(Res.string.connectnetwork_link_with_your_phone_number),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(HowYouDoinSpacing.sm))
        Text(
            stringResource(Res.string.connect_phone_intro, state.network.displayName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Instructions(step.instructions)
    }
    Spacer(Modifier.height(HowYouDoinSpacing.lg))
    step.fields.forEach { field ->
        if (field.type == InputFieldType.PHONE_NUMBER) {
            PhoneNumberInput(
                country = state.countryFor(field.id),
                number = state.inputValues[field.id].orEmpty(),
                onCountryChange = { onAction(ConnectNetworkAction.OnCountrySelect(field.id, it.isoCode)) },
                onNumberChange = { onAction(ConnectNetworkAction.OnInputChange(field.id, it)) },
                label = field.name,
                isValid = state.isValid(field),
                onDone = { if (state.canSubmitInput) onAction(ConnectNetworkAction.OnSubmitInputClick) },
            )
        } else {
            InputField(
                field = field,
                value = state.inputValues[field.id].orEmpty(),
                onValueChange = { onAction(ConnectNetworkAction.OnInputChange(field.id, it)) },
                isNumericPin = step.isMessengerHistoryPin,
            )
        }
        Spacer(Modifier.height(HowYouDoinSpacing.sm))
    }
    Spacer(Modifier.height(HowYouDoinSpacing.sm))
    Button(
        onClick = { onAction(ConnectNetworkAction.OnSubmitInputClick) },
        enabled = state.canSubmitInput && (!step.canSkip || step.fields.any {
            state.inputValues[it.id].orEmpty().isNotBlank()
        }),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (step.isMessengerHistoryPin) Res.string.connect_restore_history else Res.string.connect_continue
            )
        )
    }
    if (step.canSkip) {
        TextButton(
            onClick = { onAction(ConnectNetworkAction.OnSkipInputClick) },
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.connectnetwork_skip_for_now))
        }
    }
}

/**
 * Messenger keeps end-to-end encrypted history in a backup unlocked by the PIN set in the Messenger app.
 * The bridge prefixes the standard instructions with what went wrong (e.g. attempts remaining), so that part is
 * shown as an error.
 */
@Composable
private fun HistoryPinHeader(instructions: String) {
    val problem = instructions.substringBefore("\n\n", missingDelimiterValue = "")
        .takeIf { instructions.contains("\n\n") && it.isNotBlank() }
    Text(
        stringResource(Res.string.connectnetwork_restore_encrypted_chats),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(HowYouDoinSpacing.sm))
    Text(
        stringResource(Res.string.connectnetwork_enter_the_6_digit_pin),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(HowYouDoinSpacing.sm))
    Text(
        stringResource(Res.string.connectnetwork_messenger_allows_only_a_limited),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    problem?.let {
        Spacer(Modifier.height(HowYouDoinSpacing.sm))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun InputField(
    field: LoginInputField,
    value: String,
    onValueChange: (String) -> Unit,
    isNumericPin: Boolean = false,
) {
    val keyboardType = if (isNumericPin) KeyboardType.NumberPassword else when (field.type) {
        InputFieldType.PASSWORD -> KeyboardType.Password
        InputFieldType.PHONE_NUMBER -> KeyboardType.Phone
        InputFieldType.EMAIL -> KeyboardType.Email
        InputFieldType.TWO_FACTOR_CODE -> KeyboardType.NumberPassword
        InputFieldType.URL, InputFieldType.DOMAIN -> KeyboardType.Uri
        else -> KeyboardType.Text
    }
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            onValueChange(if (isNumericPin) input.filter(Char::isDigit).take(field.maxLength ?: 6) else input)
        },
        label = { Text(field.name) },
        supportingText = field.description.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (field.type == InputFieldType.PASSWORD) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CookiesStep(
    step: BridgeLoginStep.Cookies,
    network: ChatNetwork,
    isSubmitting: Boolean,
    error: String?,
    onAction: (ConnectNetworkAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // The bridge's own instructions are for clients that paste cookies by hand
        // ("Enter a JSON object with your cookies…"); here the web view collects them.
        Text(
            cookieLoginInstructions(network),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(HowYouDoinSpacing.md),
        )
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md)
            )
        }
        if (!isSubmitting) {
            CookieCaptureWebView(
                url = step.url,
                userAgent = step.userAgent,
                cookieNames = step.cookies.filter { it.isRequired }.map { it.cookieName }.toSet(),
                onCookiesCaptured = { onAction(ConnectNetworkAction.OnCookiesCaptured(it)) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                extractScript = step.extractScript,
            )
        }
    }
}

@Composable
private fun Connected(
    state: ConnectNetworkState,
    accountName: String?,
    onAction: (ConnectNetworkAction) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = HowYouDoinSpacing.xl)
    ) {
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF2E9E5B),
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(HowYouDoinSpacing.md))
        Text(
            stringResource(Res.string.connect_connected, state.network.displayName),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(HowYouDoinSpacing.sm))
        Text(
            listOfNotNull(
                accountName?.let { stringResource(Res.string.connect_logged_in_as, it) },
                stringResource(Res.string.connect_chats_soon, state.network.displayName),
            ).joinToString(" ") + if (state.network == ChatNetwork.MESSENGER) {
                // New encrypted devices are only picked up once the phone app refreshes its device list
                "\n\n" + stringResource(Res.string.connect_messenger_tip)
            } else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(HowYouDoinSpacing.lg))
        Button(onClick = { onAction(ConnectNetworkAction.OnDoneClick) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.connectnetwork_done))
        }
    }
}

@Composable
private fun Instructions(text: String) {
    if (text.isBlank()) return
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

/** What to do on the network's sign-in page; HowYouDoin takes over once the session exists. */
@Composable
private fun cookieLoginInstructions(network: ChatNetwork): String = when (network) {
    ChatNetwork.SLACK -> stringResource(Res.string.cookies_steps_slack)
    else -> stringResource(Res.string.cookies_steps, network.displayName)
}
