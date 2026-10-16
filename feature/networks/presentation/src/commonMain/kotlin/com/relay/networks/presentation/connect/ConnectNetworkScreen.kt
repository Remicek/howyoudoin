package com.relay.networks.presentation.connect

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
import com.relay.core.designsystem.components.NetworkBadge
import com.relay.core.designsystem.theme.RelaySpacing
import com.relay.networks.domain.BridgeLoginFlow
import com.relay.networks.domain.BridgeLoginStep
import com.relay.networks.domain.DisplayType
import com.relay.networks.domain.InputFieldType
import com.relay.networks.domain.LoginInputField
import com.relay.networks.presentation.components.CookieCaptureWebView
import com.relay.networks.presentation.components.QrCode
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
                        Spacer(Modifier.width(RelaySpacing.sm))
                        Text("Connect ${state.network.displayName}")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onAction(ConnectNetworkAction.OnBackClick) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.phase is ConnectPhase.ChooseFlow) {
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
                CookiesStep(step = phase.step, isSubmitting = state.isSubmitting, error = state.error?.asString(), onAction = onAction)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(RelaySpacing.containerPadding),
                ) {
                    when (phase) {
                        ConnectPhase.Loading -> Box(Modifier.fillMaxWidth().padding(top = RelaySpacing.xl), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        ConnectPhase.ConfigureBridge -> ConfigureBridge(state = state, onAction = onAction)
                        is ConnectPhase.ChooseFlow -> ChooseFlow(flows = phase.flows, onAction = onAction)
                        is ConnectPhase.Step -> when (val step = phase.step) {
                            is BridgeLoginStep.DisplayAndWait -> DisplayStep(step)
                            is BridgeLoginStep.UserInput -> UserInputStep(step = step, state = state, onAction = onAction)
                            is BridgeLoginStep.Cookies, is BridgeLoginStep.Complete -> Unit
                        }
                        is ConnectPhase.Connected -> Connected(state = state, accountName = phase.accountName, onAction = onAction)
                    }

                    state.error?.let { error ->
                        Spacer(Modifier.height(RelaySpacing.md))
                        Text(error.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(RelaySpacing.sm))
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
    Spacer(Modifier.height(RelaySpacing.sm))
    Text(
        "${state.network.displayName} is reached through a mautrix bridge running next to your homeserver. " +
            "Enter the public URL where its provisioning API (/_matrix/provision) is served.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(RelaySpacing.md))
    OutlinedTextField(
        value = state.bridgeUrl,
        onValueChange = { onAction(ConnectNetworkAction.OnBridgeUrlChange(it)) },
        label = { Text("Bridge URL") },
        placeholder = { Text("https://matrix.example.com/${state.network.name.lowercase()}") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(RelaySpacing.md))
    Button(
        onClick = { onAction(ConnectNetworkAction.OnSaveBridgeUrlClick) },
        enabled = state.bridgeUrl.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continue")
    }
}

@Composable
private fun ChooseFlow(flows: List<BridgeLoginFlow>, onAction: (ConnectNetworkAction) -> Unit) {
    if (flows.isEmpty()) return
    Text("How do you want to log in?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(RelaySpacing.md))
    Column(verticalArrangement = Arrangement.spacedBy(RelaySpacing.sm)) {
        flows.forEach { flow ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth().clickable { onAction(ConnectNetworkAction.OnFlowClick(flow.id)) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(RelaySpacing.md)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(flow.name, style = MaterialTheme.typography.titleMedium)
                        if (flow.description.isNotBlank()) {
                            Text(flow.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun DisplayStep(step: BridgeLoginStep.DisplayAndWait) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Instructions(step.instructions)
        Spacer(Modifier.height(RelaySpacing.lg))
        val data = step.data
        when {
            data == null -> Unit
            step.displayType == DisplayType.QR -> QrCode(data = data)
            else -> Text(
                data,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = if (step.displayType == DisplayType.EMOJI) 48.sp else 36.sp,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
                    .padding(horizontal = RelaySpacing.lg, vertical = RelaySpacing.md),
            )
        }
        Spacer(Modifier.height(RelaySpacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(RelaySpacing.sm))
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
    Instructions(step.instructions)
    Spacer(Modifier.height(RelaySpacing.md))
    step.fields.forEach { field ->
        InputField(
            field = field,
            value = state.inputValues[field.id].orEmpty(),
            onValueChange = { onAction(ConnectNetworkAction.OnInputChange(field.id, it)) },
        )
        Spacer(Modifier.height(RelaySpacing.sm))
    }
    Spacer(Modifier.height(RelaySpacing.sm))
    Button(
        onClick = { onAction(ConnectNetworkAction.OnSubmitInputClick) },
        enabled = state.canSubmitInput,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continue")
    }
}

@Composable
private fun InputField(field: LoginInputField, value: String, onValueChange: (String) -> Unit) {
    val keyboardType = when (field.type) {
        InputFieldType.PASSWORD -> KeyboardType.Password
        InputFieldType.PHONE_NUMBER -> KeyboardType.Phone
        InputFieldType.EMAIL -> KeyboardType.Email
        InputFieldType.TWO_FACTOR_CODE -> KeyboardType.NumberPassword
        InputFieldType.URL, InputFieldType.DOMAIN -> KeyboardType.Uri
        else -> KeyboardType.Text
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
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
    isSubmitting: Boolean,
    error: String?,
    onAction: (ConnectNetworkAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            step.instructions.ifBlank { "Log in below. Relay hands the session to your bridge once you're in." },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(RelaySpacing.md),
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = RelaySpacing.md))
        }
        if (!isSubmitting) {
            CookieCaptureWebView(
                url = step.url,
                userAgent = step.userAgent,
                cookieNames = step.cookies.filter { it.isRequired }.map { it.cookieName }.toSet(),
                onCookiesCaptured = { onAction(ConnectNetworkAction.OnCookiesCaptured(it)) },
                modifier = Modifier.fillMaxWidth().weight(1f),
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = RelaySpacing.xl)) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF2E9E5B), modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(RelaySpacing.md))
        Text("${state.network.displayName} connected", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(RelaySpacing.sm))
        Text(
            "Your chats will appear in the inbox as the bridge syncs them${accountName?.let { " ($it)" }.orEmpty()}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(RelaySpacing.lg))
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
