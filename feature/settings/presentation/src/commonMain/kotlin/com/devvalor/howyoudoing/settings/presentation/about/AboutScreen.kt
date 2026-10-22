package com.devvalor.howyoudoing.settings.presentation.about

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.settings.domain.AppInfo
import com.devvalor.howyoudoing.settings.presentation.components.SettingsRow
import com.devvalor.howyoudoing.settings.presentation.components.SettingsScaffold
import com.devvalor.howyoudoing.settings.presentation.components.SettingsSectionHeader
import org.koin.compose.koinInject

@Composable
fun AboutRoot(onLicensesClick: () -> Unit, onBack: () -> Unit, appInfo: AppInfo = koinInject()) {
    AboutScreen(appInfo = appInfo, onLicensesClick = onLicensesClick, onBack = onBack)
}

@Composable
fun AboutScreen(appInfo: AppInfo, onLicensesClick: () -> Unit, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    SettingsScaffold(title = "About", onBack = onBack) {
        item {
            SettingsRow(title = "HowYouDoin", subtitle = "Version ${appInfo.versionName}", icon = Icons.Rounded.Info)
        }
        item {
            SettingsRow(
                title = "Source code",
                subtitle = "Free software under the GNU AGPL v3. You may study, change and share it.",
                icon = Icons.Rounded.Code,
                onClick = { uriHandler.openUri(appInfo.sourceCodeUrl) },
                trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Opens in the browser") },
            )
        }
        item {
            SettingsRow(
                title = "Open-source licenses",
                subtitle = "The libraries HowYouDoin is built with",
                icon = Icons.Rounded.Gavel,
                onClick = onLicensesClick,
            )
        }
        item { SettingsSectionHeader("Good to know") }
        item {
            AboutParagraph(
                "HowYouDoin is an unofficial app. It is not made, endorsed or supported by Meta (WhatsApp, Messenger, " +
                    "Instagram), Telegram or Signal. Those services don't officially allow third-party apps, so they could " +
                    "limit or block an account that uses one. Use it at your own risk."
            )
        }
        item {
            AboutParagraph(
                "Your accounts connect straight from this phone. Messages, contacts and login data stay on the phone and " +
                    "go only to the networks themselves; HowYouDoin has no servers of its own in this mode and collects no " +
                    "analytics. If \"Back up logins\" is on, the logins are also in your phone's Google backup, end-to-end " +
                    "encrypted with your screen lock. Crash reports stay on the phone unless you share them."
            )
        }
    }
}

@Composable
private fun AboutParagraph(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
    )
}
