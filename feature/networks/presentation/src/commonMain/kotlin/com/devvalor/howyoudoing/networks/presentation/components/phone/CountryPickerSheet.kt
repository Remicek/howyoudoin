package com.devvalor.howyoudoing.networks.presentation.components.phone

import howyoudoin.feature.networks.presentation.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import howyoudoin.feature.networks.presentation.generated.resources.countrypicker_choose_country
import howyoudoin.feature.networks.presentation.generated.resources.countrypicker_search_country_or_code
import howyoudoin.feature.networks.presentation.generated.resources.countrypicker_selected
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPickerSheet(
    selected: Country,
    onSelect: (Country) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val countries = remember(query) {
        val q = query.trim().removePrefix("+").lowercase()
        if (q.isEmpty()) {
            // Selected country first so it's visible without scrolling
            listOf(selected) + Country.all.filter { it != selected }
        } else {
            Country.all.filter { country ->
                country.displayName.lowercase().contains(q) || country.name.lowercase().contains(q) || country.dialCode.startsWith(q) || country.isoCode.lowercase() == q
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.85f)) {
            Text(
                stringResource(Res.string.countrypicker_choose_country),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(Res.string.countrypicker_search_country_or_code)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.sm),
            )
            LazyColumn {
                items(countries, key = { it.isoCode }) { country ->
                    CountryRow(country = country, isSelected = country == selected, onClick = { onSelect(country) })
                }
            }
        }
    }
}

@Composable
private fun CountryRow(country: Country, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .padding(horizontal = HowYouDoinSpacing.md, vertical = 12.dp),
    ) {
        Text(country.flag, fontSize = 24.sp)
        Spacer(Modifier.width(HowYouDoinSpacing.md))
        Text(country.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            "+${country.dialCode}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isSelected) {
            Spacer(Modifier.width(HowYouDoinSpacing.sm))
            Icon(Icons.Rounded.Check, contentDescription = stringResource(Res.string.countrypicker_selected), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
