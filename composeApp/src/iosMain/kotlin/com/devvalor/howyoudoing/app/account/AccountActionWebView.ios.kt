package com.devvalor.howyoudoing.app.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.devvalor.howyoudoing.core.domain.ondevice.AccountAction

/** Not built for iOS yet. */
@Composable
actual fun AccountActionWebView(
    action: AccountAction,
    allowedHosts: Set<String>,
    onCookies: (Map<String, String>) -> Unit,
    modifier: Modifier,
) = Unit
