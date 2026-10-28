package com.devvalor.howyoudoing.settings.presentation.about

import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.about_good_to_know
import howyoudoin.feature.settings.presentation.generated.resources.about_licenses_sub
import howyoudoin.feature.settings.presentation.generated.resources.about_opens_browser
import howyoudoin.feature.settings.presentation.generated.resources.about_privacy
import howyoudoin.feature.settings.presentation.generated.resources.about_privacy_policy
import howyoudoin.feature.settings.presentation.generated.resources.about_privacy_policy_sub
import howyoudoin.feature.settings.presentation.generated.resources.about_source
import howyoudoin.feature.settings.presentation.generated.resources.about_source_sub
import howyoudoin.feature.settings.presentation.generated.resources.about_terms_sub
import howyoudoin.feature.settings.presentation.generated.resources.about_title
import howyoudoin.feature.settings.presentation.generated.resources.about_trademarks
import howyoudoin.feature.settings.presentation.generated.resources.about_unofficial
import howyoudoin.feature.settings.presentation.generated.resources.about_version
import howyoudoin.feature.settings.presentation.generated.resources.settings_licenses
import howyoudoin.feature.settings.presentation.generated.resources.settings_terms
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Policy
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
fun AboutRoot(
    onLicensesClick: () -> Unit,
    onTermsClick: () -> Unit,
    onBack: () -> Unit,
    appInfo: AppInfo = koinInject()
) {
    AboutScreen(appInfo = appInfo, onLicensesClick = onLicensesClick, onTermsClick = onTermsClick, onBack = onBack)
}

@Composable
fun AboutScreen(appInfo: AppInfo, onLicensesClick: () -> Unit, onTermsClick: () -> Unit, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    SettingsScaffold(title = stringResource(Res.string.about_title), onBack = onBack) {
        item {
            SettingsRow(
                title = "HowYouDoin",
                subtitle = stringResource(Res.string.about_version, appInfo.versionName),
                icon = Icons.Rounded.Info
            )
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.about_source),
                subtitle = stringResource(Res.string.about_source_sub),
                icon = Icons.Rounded.Code,
                onClick = { uriHandler.openUri(appInfo.sourceCodeUrl) },
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(Res.string.about_opens_browser)
                    )
                },
            )
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.about_privacy_policy),
                subtitle = stringResource(Res.string.about_privacy_policy_sub),
                icon = Icons.Rounded.Shield,
                onClick = { uriHandler.openUri(appInfo.privacyPolicyUrl) },
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(Res.string.about_opens_browser)
                    )
                },
            )
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_licenses),
                subtitle = stringResource(Res.string.about_licenses_sub),
                icon = Icons.Rounded.Gavel,
                onClick = onLicensesClick,
            )
        }
        item {
            SettingsRow(
                title = stringResource(Res.string.settings_terms),
                subtitle = stringResource(Res.string.about_terms_sub),
                icon = Icons.Rounded.Policy,
                onClick = onTermsClick,
            )
        }
        item { SettingsSectionHeader(stringResource(Res.string.about_good_to_know)) }
        item {
            AboutParagraph(
                stringResource(Res.string.about_unofficial)
            )
        }
        item {
            AboutParagraph(
                stringResource(Res.string.about_privacy)
            )
        }
        item {
            AboutParagraph(
                stringResource(Res.string.about_trademarks)
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
