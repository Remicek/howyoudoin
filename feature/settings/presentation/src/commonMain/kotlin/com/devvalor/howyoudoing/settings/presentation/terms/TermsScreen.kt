package com.devvalor.howyoudoing.settings.presentation.terms

import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import howyoudoin.feature.settings.presentation.generated.resources.Res
import howyoudoin.feature.settings.presentation.generated.resources.settings_terms
import howyoudoin.feature.settings.presentation.generated.resources.terms_intro
import howyoudoin.feature.settings.presentation.generated.resources.terms_unofficial_title
import howyoudoin.feature.settings.presentation.generated.resources.terms_unofficial_body
import howyoudoin.feature.settings.presentation.generated.resources.terms_risk_title
import howyoudoin.feature.settings.presentation.generated.resources.terms_risk_body
import howyoudoin.feature.settings.presentation.generated.resources.terms_as_is_title
import howyoudoin.feature.settings.presentation.generated.resources.terms_as_is_body
import howyoudoin.feature.settings.presentation.generated.resources.terms_liability_title
import howyoudoin.feature.settings.presentation.generated.resources.terms_liability_body
import howyoudoin.feature.settings.presentation.generated.resources.terms_responsibilities_title
import howyoudoin.feature.settings.presentation.generated.resources.terms_responsibilities_body
import howyoudoin.feature.settings.presentation.generated.resources.terms_license_title
import howyoudoin.feature.settings.presentation.generated.resources.terms_license_body
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.settings.presentation.components.SettingsScaffold

/** Keep in step with docs/terms-of-use.md, which is the version linked from the store listing. */
private data class TermsSection(val heading: StringResource, val body: StringResource)

private val sections = listOf(
    TermsSection(Res.string.terms_unofficial_title, Res.string.terms_unofficial_body),
    TermsSection(Res.string.terms_risk_title, Res.string.terms_risk_body),
    TermsSection(Res.string.terms_as_is_title, Res.string.terms_as_is_body),
    TermsSection(Res.string.terms_liability_title, Res.string.terms_liability_body),
    TermsSection(Res.string.terms_responsibilities_title, Res.string.terms_responsibilities_body),
    TermsSection(Res.string.terms_license_title, Res.string.terms_license_body),
)

@Composable
fun TermsRoot(onBack: () -> Unit) {
    SettingsScaffold(title = stringResource(Res.string.settings_terms), onBack = onBack) {
        item {
            Text(
                stringResource(Res.string.terms_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
            )
        }
        sections.forEach { section ->
            item {
                TermsSectionText(
                    section,
                    Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm)
                )
            }
        }
    }
}

@Composable
private fun TermsSectionText(section: TermsSection, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(section.heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(HowYouDoinSpacing.xs))
        Text(
            stringResource(section.body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
