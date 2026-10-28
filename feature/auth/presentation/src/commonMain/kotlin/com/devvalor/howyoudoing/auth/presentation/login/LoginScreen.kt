package com.devvalor.howyoudoing.auth.presentation.login

import howyoudoin.feature.auth.presentation.generated.resources.Res
import howyoudoin.feature.auth.presentation.generated.resources.login_demo
import howyoudoin.feature.auth.presentation.generated.resources.login_get_started
import howyoudoin.feature.auth.presentation.generated.resources.login_hide_password
import howyoudoin.feature.auth.presentation.generated.resources.login_homeserver
import howyoudoin.feature.auth.presentation.generated.resources.login_password
import howyoudoin.feature.auth.presentation.generated.resources.login_server_intro
import howyoudoin.feature.auth.presentation.generated.resources.login_show_password
import howyoudoin.feature.auth.presentation.generated.resources.login_sign_in_server
import howyoudoin.feature.auth.presentation.generated.resources.login_tagline
import howyoudoin.feature.auth.presentation.generated.resources.login_username
import howyoudoin.feature.auth.presentation.generated.resources.privacy_direct_text
import howyoudoin.feature.auth.presentation.generated.resources.privacy_direct_title
import howyoudoin.feature.auth.presentation.generated.resources.privacy_no_servers_text
import howyoudoin.feature.auth.presentation.generated.resources.privacy_no_servers_title
import howyoudoin.feature.auth.presentation.generated.resources.privacy_on_phone_text
import howyoudoin.feature.auth.presentation.generated.resources.privacy_on_phone_title
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.core.designsystem.components.NetworkBadge
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.presentation.util.ObserveAsEvents
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LoginRoot(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            LoginEvent.Success -> onLoginSuccess()
        }
    }

    LoginScreen(
        state = state,
        onAction = viewModel::onAction
    )
}

@Composable
fun LoginScreen(
    state: LoginState,
    onAction: (LoginAction) -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HowYouDoinSpacing.containerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(HowYouDoinSpacing.xl))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(80.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
            ) {
                Icon(
                    Icons.Rounded.Forum,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
            }

            Spacer(Modifier.height(HowYouDoinSpacing.md))
            Text("HowYouDoin", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(HowYouDoinSpacing.xs))
            Text(
                stringResource(Res.string.login_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(HowYouDoinSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.xs)) {
                ChatNetwork.bridged.forEach { network ->
                    NetworkBadge(network = network, size = 22.dp, enabled = network.isSupported)
                }
            }

            Spacer(Modifier.height(HowYouDoinSpacing.xl))

            Button(
                onClick = { onAction(LoginAction.OnLocalClick) },
                enabled = !state.isLoggingIn,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(Res.string.login_get_started), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(HowYouDoinSpacing.md))
            PrivacyCard()

            Spacer(Modifier.height(HowYouDoinSpacing.xl))
            HorizontalDivider()
            Spacer(Modifier.height(HowYouDoinSpacing.lg))

            Text(
                stringResource(Res.string.login_server_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(HowYouDoinSpacing.md))

            LoginField(
                value = state.homeserver,
                onValueChange = { onAction(LoginAction.OnHomeserverChange(it)) },
                label = stringResource(Res.string.login_homeserver),
                placeholder = "matrix.example.com",
                icon = Icons.Rounded.Dns,
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(HowYouDoinSpacing.sm))
            LoginField(
                value = state.username,
                onValueChange = { onAction(LoginAction.OnUsernameChange(it)) },
                label = stringResource(Res.string.login_username),
                placeholder = "@you:example.com",
                icon = Icons.Rounded.Person,
            )
            Spacer(Modifier.height(HowYouDoinSpacing.sm))
            LoginField(
                value = state.password,
                onValueChange = { onAction(LoginAction.OnPasswordChange(it)) },
                label = stringResource(Res.string.login_password),
                placeholder = "",
                icon = Icons.Rounded.Lock,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                isPassword = !state.isPasswordVisible,
                trailing = {
                    IconButton(onClick = { onAction(LoginAction.OnTogglePasswordVisibility) }) {
                        Icon(
                            if (state.isPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = stringResource(
                                if (state.isPasswordVisible) {
                                    Res.string.login_hide_password
                                } else {
                                    Res.string.login_show_password
                                }
                            ),
                        )
                    }
                },
            )

            state.error?.let { error ->
                Spacer(Modifier.height(HowYouDoinSpacing.sm))
                Text(
                    error.asString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(HowYouDoinSpacing.lg))

            FilledTonalButton(
                onClick = { onAction(LoginAction.OnLoginClick) },
                enabled = state.canLogin && !state.isLoggingIn,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.isLoggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(Res.string.login_sign_in_server), style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(HowYouDoinSpacing.sm))
            OutlinedButton(
                onClick = { onAction(LoginAction.OnDemoClick) },
                enabled = !state.isLoggingIn,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(Res.string.login_demo), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(HowYouDoinSpacing.lg))
        }
    }
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    isPassword: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = trailing,
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** What "no servers of ours" means, in plain words, before anything is connected. */
@Composable
private fun PrivacyCard() {
    Column(
        verticalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
            .padding(HowYouDoinSpacing.md),
    ) {
        PrivacyPoint(
            Icons.Rounded.PhoneAndroid,
            stringResource(Res.string.privacy_on_phone_title),
            stringResource(Res.string.privacy_on_phone_text),
        )
        PrivacyPoint(
            Icons.Rounded.SwapHoriz,
            stringResource(Res.string.privacy_direct_title),
            stringResource(Res.string.privacy_direct_text),
        )
        PrivacyPoint(
            Icons.Rounded.CloudOff,
            stringResource(Res.string.privacy_no_servers_title),
            stringResource(Res.string.privacy_no_servers_text),
        )
    }
}

@Composable
private fun PrivacyPoint(icon: ImageVector, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(HowYouDoinSpacing.sm)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
