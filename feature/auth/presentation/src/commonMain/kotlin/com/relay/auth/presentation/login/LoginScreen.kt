package com.relay.auth.presentation.login

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
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.relay.core.designsystem.components.NetworkBadge
import com.relay.core.designsystem.theme.RelaySpacing
import com.relay.core.domain.network.ChatNetwork
import com.relay.core.presentation.util.ObserveAsEvents
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
                .padding(horizontal = RelaySpacing.containerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(RelaySpacing.xl))

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

            Spacer(Modifier.height(RelaySpacing.md))
            Text("Relay", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(RelaySpacing.xs))
            Text(
                "All your chats in one inbox",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(RelaySpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(RelaySpacing.xs)) {
                ChatNetwork.bridged.forEach { network ->
                    NetworkBadge(network = network, size = 22.dp, enabled = network.isSupported)
                }
            }

            Spacer(Modifier.height(RelaySpacing.xl))

            Text(
                "Sign in to the Matrix homeserver that runs your bridges.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(RelaySpacing.md))

            LoginField(
                value = state.homeserver,
                onValueChange = { onAction(LoginAction.OnHomeserverChange(it)) },
                label = "Homeserver",
                placeholder = "matrix.example.com",
                icon = Icons.Rounded.Dns,
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(RelaySpacing.sm))
            LoginField(
                value = state.username,
                onValueChange = { onAction(LoginAction.OnUsernameChange(it)) },
                label = "Username",
                placeholder = "@you:example.com",
                icon = Icons.Rounded.Person,
            )
            Spacer(Modifier.height(RelaySpacing.sm))
            LoginField(
                value = state.password,
                onValueChange = { onAction(LoginAction.OnPasswordChange(it)) },
                label = "Password",
                placeholder = "",
                icon = Icons.Rounded.Lock,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                isPassword = !state.isPasswordVisible,
                trailing = {
                    IconButton(onClick = { onAction(LoginAction.OnTogglePasswordVisibility) }) {
                        Icon(
                            if (state.isPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (state.isPasswordVisible) "Hide password" else "Show password",
                        )
                    }
                },
            )

            state.error?.let { error ->
                Spacer(Modifier.height(RelaySpacing.sm))
                Text(
                    error.asString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(RelaySpacing.lg))

            Button(
                onClick = { onAction(LoginAction.OnLoginClick) },
                enabled = state.canLogin && !state.isLoggingIn,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.isLoggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Sign in", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(RelaySpacing.sm))
            OutlinedButton(
                onClick = { onAction(LoginAction.OnDemoClick) },
                enabled = !state.isLoggingIn,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Try demo mode", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(RelaySpacing.lg))
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
