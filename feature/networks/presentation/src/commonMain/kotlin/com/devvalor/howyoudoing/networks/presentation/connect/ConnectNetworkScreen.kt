package com.devvalor.howyoudoing.networks.presentation.connect

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
                        Text("Connect ${state.network.displayName}")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onAction(ConnectNetworkAction.OnBackClick) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // A Matrix account has no bridge to configure
                    if (state.phase is ConnectPhase.ChooseFlow && state.canEditBridgeUrl && state.network != ChatNetwork.MATRIX) {
                        IconButton(onClick = { onAction(ConnectNetworkAction.OnEditBridgeUrlClick) }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Bridge settings")
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
                CookiesStep(step = phase.step, network = state.network, isSubmitting = state.isSubmitting, error = state.error?.asString(), onAction = onAction)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(HowYouDoinSpacing.containerPadding),
                ) {
                    when (phase) {
                        ConnectPhase.Loading -> Box(Modifier.fillMaxWidth().padding(top = HowYouDoinSpacing.xl), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        ConnectPhase.ConfigureBridge -> ConfigureBridge(state = state, onAction = onAction)
                        is ConnectPhase.ChooseFlow -> ChooseFlow(network = state.network, flows = phase.flows, onAction = onAction)
                        is ConnectPhase.Step -> when (val step = phase.step) {
                            is BridgeLoginStep.DisplayAndWait -> DisplayStep(step, state.network)
                            is BridgeLoginStep.UserInput -> UserInputStep(step = step, state = state, onAction = onAction)
                            is BridgeLoginStep.Cookies, is BridgeLoginStep.Complete -> Unit
                        }
                        is ConnectPhase.Connected -> Connected(state = state, accountName = phase.accountName, onAction = onAction)
                    }

                    state.error?.let { error ->
                        Spacer(Modifier.height(HowYouDoinSpacing.md))
                        Text(error.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(HowYouDoinSpacing.sm))
                        OutlinedButton(onClick = { onAction(ConnectNetworkAction.OnRetryClick) }) {
                            Text("Try again")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigureBridge(state: ConnectNetworkState, onAction: (ConnectNetworkAction) -> Unit) {
    Text("Bridge address", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(HowYouDoinSpacing.sm))
    Text(
        "${state.network.displayName} is reached through a mautrix bridge running next to your homeserver. " +
            "Enter the public URL where its provisioning API (/_matrix/provision) is served.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(HowYouDoinSpacing.md))
    OutlinedTextField(
        value = state.bridgeUrl,
        onValueChange = { onAction(ConnectNetworkAction.OnBridgeUrlChange(it)) },
        label = { Text("Bridge URL") },
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
        Text("Continue")
    }
}

@Composable
private fun ChooseFlow(network: ChatNetwork, flows: List<BridgeLoginFlow>, onAction: (ConnectNetworkAction) -> Unit) {
    if (flows.isEmpty()) return
    Text("How do you want to log in?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
                            Text(label.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (label.isRecommended) {
                                Spacer(Modifier.width(HowYouDoinSpacing.sm))
                                Text(
                                    "Recommended",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (label.description.isNotBlank()) {
                            Text(label.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            "HowYouDoin is an unofficial app, not made or supported by Telegram. It uses Telegram's public API, " +
                "which Telegram allows other apps to use."
        } else if (network == ChatNetwork.MATRIX) {
            "Matrix is an open standard: HowYouDoin is one of many apps that work with any Matrix homeserver."
        } else {
            "HowYouDoin is an unofficial app, not made or supported by ${network.displayName}. ${network.displayName} " +
                "doesn't officially allow other apps, so it could limit an account that uses one."
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
                        "In WhatsApp, open Settings → Linked devices → Link a device, then tap " +
                            "\"Link with phone number instead\" and enter or paste this code.",
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
            Text("Waiting for confirmation…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("Link with your phone number", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(HowYouDoinSpacing.sm))
        Text(
            "Enter the number of your ${state.network.displayName} account. You'll get a code to type into " +
                "${state.network.displayName} on your phone, under Linked devices.",
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
        enabled = state.canSubmitInput && (!step.canSkip || step.fields.any { state.inputValues[it.id].orEmpty().isNotBlank() }),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (step.isMessengerHistoryPin) "Restore history" else "Continue")
    }
    if (step.canSkip) {
        TextButton(
            onClick = { onAction(ConnectNetworkAction.OnSkipInputClick) },
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Skip for now")
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
    Text("Restore encrypted chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(HowYouDoinSpacing.sm))
    Text(
        "Enter the 6-digit PIN you set in Messenger for end-to-end encrypted chats. It unlocks your saved message " +
            "history so older encrypted messages appear here too.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(HowYouDoinSpacing.sm))
    Text(
        "Messenger allows only a limited number of wrong attempts. If you're not sure of your PIN, skip this; new " +
            "messages arrive either way.",
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
        visualTransformation = if (field.type == InputFieldType.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
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
            "1. Enter your ${network.displayName} email or phone number and password, then tap Log in.\n" +
                "2. If it then shows \"Continue as <your name>\", tap it. Finish any \"confirm it's you\" steps " +
                "here too.\n" +
                "HowYouDoin continues by itself once you're in. Pinch to zoom if the page looks small.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(HowYouDoinSpacing.md),
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md))
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = HowYouDoinSpacing.xl)) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF2E9E5B), modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(HowYouDoinSpacing.md))
        Text("${state.network.displayName} connected", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(HowYouDoinSpacing.sm))
        Text(
            buildString {
                accountName?.let { append("Logged in as $it. ") }
                append("Your ${state.network.displayName} chats will show up in the inbox over the next few minutes.")
                if (state.network == ChatNetwork.MESSENGER) {
                    // New encrypted devices are only picked up once the phone app refreshes its device list
                    append("\n\nTip: open Messenger on your phone once so it recognises HowYouDoin as one of your " +
                        "devices. Until then, encrypted messages sent from that phone may not arrive here.")
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(HowYouDoinSpacing.lg))
        Button(onClick = { onAction(ConnectNetworkAction.OnDoneClick) }, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}

@Composable
private fun Instructions(text: String) {
    if (text.isBlank()) return
    Text(text, style = MaterialTheme.typography.bodyLarge)
}
