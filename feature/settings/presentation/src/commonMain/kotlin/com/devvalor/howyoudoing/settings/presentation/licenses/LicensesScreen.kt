package com.devvalor.howyoudoing.settings.presentation.licenses

import howyoudoin.feature.settings.presentation.generated.resources.licenses_app
import howyoudoin.feature.settings.presentation.generated.resources.licenses_intro
import howyoudoin.feature.settings.presentation.generated.resources.licenses_missing
import howyoudoin.feature.settings.presentation.generated.resources.licenses_native
import howyoudoin.feature.settings.presentation.generated.resources.settings_licenses
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing
import com.devvalor.howyoudoing.settings.presentation.components.SettingsScaffold
import com.devvalor.howyoudoing.settings.presentation.components.SettingsSectionHeader
import howyoudoin.feature.settings.presentation.generated.resources.Res
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.koin.compose.viewmodel.koinViewModel

data class LibraryLicense(
    val name: String,
    val version: String,
    val license: String,
    /** Opened on tap: the project's site, else the license text. */
    val url: String?,
    val usedFor: String? = null,
)

data class LicensesState(
    val isLoading: Boolean = true,
    /** Go and Rust code compiled into the app: the chat network clients. */
    val networkLibraries: List<LibraryLicense> = emptyList(),
    /** Kotlin / Android libraries. */
    val appLibraries: List<LibraryLicense> = emptyList(),
)

/**
 * Lists generated at build time: `files/aboutlibraries.json` (Gradle dependencies, by the AboutLibraries plugin)
 * and `files/native-licenses.json` (Go modules and Rust crates, by bridge-mobile/scripts/generate-licenses.sh).
 */
@OptIn(ExperimentalResourceApi::class)
class LicensesViewModel : ViewModel() {

    private val _state = MutableStateFlow(LicensesState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val app = runCatching {
                parseAboutLibraries(
                    Res.readBytes("files/aboutlibraries.json").decodeToString()
                )
            }.getOrDefault(emptyList())
            val native =
                runCatching { parseNative(Res.readBytes("files/native-licenses.json").decodeToString()) }.getOrDefault(
                    emptyList()
                )
            _state.value = LicensesState(isLoading = false, networkLibraries = native, appLibraries = app)
        }
    }

    private fun JsonObject.str(key: String) =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun parseAboutLibraries(text: String): List<LibraryLicense> {
        val root = Json.parseToJsonElement(text).jsonObject
        val licenses = root["licenses"]?.jsonObject.orEmpty()
        return root["libraries"]?.jsonArray.orEmpty().mapNotNull { element ->
            val lib = element as? JsonObject ?: return@mapNotNull null
            val licenseIds =
                (lib["licenses"] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    .orEmpty()
            val licenseObjects = licenseIds.mapNotNull { licenses[it] as? JsonObject }
            LibraryLicense(
                name = lib.str("name") ?: lib.str("uniqueId") ?: return@mapNotNull null,
                version = lib.str("artifactVersion").orEmpty(),
                license = licenseObjects.mapNotNull { it.str("name") }.ifEmpty { licenseIds }.joinToString(", ")
                    .ifEmpty { "See website" },
                url = lib.str("website") ?: licenseObjects.firstNotNullOfOrNull { it.str("url") },
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun parseNative(text: String): List<LibraryLicense> =
        Json.parseToJsonElement(text).jsonObject["libraries"]?.jsonArray.orEmpty().mapNotNull { element ->
            val lib = element as? JsonObject ?: return@mapNotNull null
            LibraryLicense(
                name = lib.str("name") ?: return@mapNotNull null,
                version = lib.str("version").orEmpty(),
                license = lib.str("license") ?: "Unknown",
                url = lib.str("url"),
                usedFor = lib.str("usedFor"),
            )
        }
}

@Composable
fun LicensesRoot(onBack: () -> Unit, viewModel: LicensesViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LicensesScreen(state, onBack)
}

@Composable
fun LicensesScreen(state: LicensesState, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    SettingsScaffold(title = stringResource(Res.string.settings_licenses), onBack = onBack) {
        item {
            Text(
                stringResource(Res.string.licenses_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
            )
        }
        if (state.networkLibraries.isNotEmpty()) {
            item { SettingsSectionHeader(stringResource(Res.string.licenses_native)) }
            items(state.networkLibraries, key = { "native:" + it.name + ":" + it.version }) { lib ->
                LibraryRow(lib) { lib.url?.let(uriHandler::openUri) }
            }
        }
        if (state.appLibraries.isNotEmpty()) {
            item { SettingsSectionHeader(stringResource(Res.string.licenses_app)) }
            items(state.appLibraries, key = { "app:" + it.name + it.version }) { lib ->
                LibraryRow(lib) { lib.url?.let(uriHandler::openUri) }
            }
        }
        if (!state.isLoading && state.networkLibraries.isEmpty() && state.appLibraries.isEmpty()) {
            item {
                Text(
                    stringResource(Res.string.licenses_missing),
                    modifier = Modifier.padding(HowYouDoinSpacing.md),
                )
            }
        }
    }
}

@Composable
private fun LibraryRow(lib: LibraryLicense, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (lib.url != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(lib.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(lib.version.takeIf { it.isNotBlank() }, lib.usedFor).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(HowYouDoinSpacing.sm))
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                lib.license,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp)
                    .padding(horizontal = HowYouDoinSpacing.sm, vertical = HowYouDoinSpacing.xxs),
            )
        }
    }
}
