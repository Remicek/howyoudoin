package com.devvalor.howyoudoing.app.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.AccountAction
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import howyoudoin.composeapp.generated.resources.Res
import howyoudoin.composeapp.generated.resources.account_action_cancel
import howyoudoin.composeapp.generated.resources.account_action_done
import howyoudoin.composeapp.generated.resources.account_action_text
import howyoudoin.composeapp.generated.resources.account_action_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The network's own page the user opened with Reconnect (e.g. Facebook's cookie choices), signed in as their account,
 * with what to do there. Done reconnects the network.
 */
@Composable
fun AccountActionRoot(monitor: ConnectionMonitor = koinInject()) {
    val action by monitor.pendingAction.collectAsStateWithLifecycle()
    val current = action ?: return
    var cookies by remember(current) { mutableStateOf<Map<String, String>>(emptyMap()) }
    Dialog(
        onDismissRequest = { monitor.finishAction(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(Res.string.account_action_title, current.network.displayName),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(Res.string.account_action_text, current.network.displayName),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                AccountActionWebView(
                    action = current,
                    allowedHosts = accountActionHosts(current.network),
                    onCookies = { cookies = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { monitor.finishAction(null) }) {
                        Text(stringResource(Res.string.account_action_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { monitor.finishAction(cookies) }) {
                        Text(stringResource(Res.string.account_action_done))
                    }
                }
            }
        }
    }
}

/**
 * Shows [action]'s page signed in with its cookies, staying on [allowedHosts], and reports the site's cookies as they
 * change ([onCookies]) so a login the site renewed is kept.
 */
@Composable
expect fun AccountActionWebView(
    action: AccountAction,
    allowedHosts: Set<String>,
    onCookies: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
)

/** The sites each network sends the user to for such steps. */
internal fun accountActionHosts(network: ChatNetwork): Set<String> = when (network) {
    ChatNetwork.MESSENGER -> setOf("messenger.com", "facebook.com", "fbcdn.net")
    ChatNetwork.INSTAGRAM -> setOf("instagram.com", "facebook.com", "cdninstagram.com", "fbcdn.net")
    else -> emptySet()
}

/** The host of an http(s) [url], lower-cased; null if it has none. */
internal fun hostOf(url: String): String? =
    url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':').lowercase().ifEmpty { null }

/** Whether the page may go to [url]: https on one of [allowedHosts] (or its subdomains); anything with none given. */
internal fun isAllowed(url: String, allowedHosts: Set<String>): Boolean {
    if (allowedHosts.isEmpty()) return true
    val host = hostOf(url) ?: return false
    return url.startsWith("https://") && allowedHosts.any { host == it || host.endsWith(".$it") }
}

/** The domain the account's cookies go on for [url] ("www.messenger.com" → "messenger.com"). */
internal fun cookieDomain(url: String): String? = hostOf(url)?.split('.')?.takeLast(2)?.joinToString(".")

/** Parses a `name=value; name2=value2` cookie header. */
internal fun parseCookies(header: String?): Map<String, String> =
    header.orEmpty().split(";").mapNotNull { part ->
        val name = part.substringBefore("=", "").trim()
        if (name.isEmpty()) null else name to part.substringAfter("=").trim()
    }.toMap()

internal const val COOKIE_POLL_MILLIS = 1_000L
