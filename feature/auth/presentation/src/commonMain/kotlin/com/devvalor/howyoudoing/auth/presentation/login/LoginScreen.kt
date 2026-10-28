package com.devvalor.howyoudoing.auth.presentation.login

import howyoudoin.feature.auth.presentation.generated.resources.Res
import howyoudoin.feature.auth.presentation.generated.resources.login_demo
import howyoudoin.feature.auth.presentation.generated.resources.login_get_started
import howyoudoin.feature.auth.presentation.generated.resources.login_tagline
import howyoudoin.feature.auth.presentation.generated.resources.privacy_direct_text
import howyoudoin.feature.auth.presentation.generated.resources.privacy_direct_title
import howyoudoin.feature.auth.presentation.generated.resources.privacy_no_servers_text
import howyoudoin.feature.auth.presentation.generated.resources.privacy_no_servers_title
import howyoudoin.feature.auth.presentation.generated.resources.privacy_on_phone_text
import howyoudoin.feature.auth.presentation.generated.resources.privacy_on_phone_title
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.core.designsystem.components.NetworkBadge
import com.devvalor.howyoudoing.core.designsystem.theme.AppLogo
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
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
                .padding(horizontal = HowYouDoinSpacing.containerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WelcomeContent(state, onAction)
            }
            // Trying the app is the secondary choice, so it waits at the bottom
            TextButton(
                onClick = { onAction(LoginAction.OnDemoClick) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(Res.string.login_demo), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(HowYouDoinSpacing.sm))
        }
    }
}

@Composable
private fun WelcomeContent(state: LoginState, onAction: (LoginAction) -> Unit) {
    Spacer(Modifier.height(HowYouDoinSpacing.xl))

    // The name is right below, so the logo itself is decorative
    Image(AppLogo, contentDescription = null, modifier = Modifier.size(96.dp))

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
        state.networks.forEach { network ->
            NetworkBadge(network = network, size = 22.dp)
        }
    }

    Spacer(Modifier.height(HowYouDoinSpacing.xl))

    Button(
        onClick = { onAction(LoginAction.OnLocalClick) },
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(stringResource(Res.string.login_get_started), style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(HowYouDoinSpacing.md))
    PrivacyCard()

    Spacer(Modifier.height(HowYouDoinSpacing.lg))
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
