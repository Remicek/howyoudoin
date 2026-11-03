package com.devvalor.howyoudoing.networks.presentation.components.phone

import howyoudoin.feature.networks.presentation.generated.resources.Res
import howyoudoin.feature.networks.presentation.generated.resources.phone_hint
import howyoudoin.feature.networks.presentation.generated.resources.phone_incomplete
import howyoudoin.feature.networks.presentation.generated.resources.phone_linked_as
import howyoudoin.feature.networks.presentation.generated.resources.phone_choose_country
import howyoudoin.feature.networks.presentation.generated.resources.phone_use_own_number
import howyoudoin.feature.networks.presentation.generated.resources.phone_own_number_unavailable
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinSpacing

/**
 * Country selector + national number field. [number] is what the user typed (without the
 * country code); the caller combines both via [PhoneNumbers.toE164].
 */
@Composable
fun PhoneNumberInput(
    country: Country,
    number: String,
    onCountryChange: (Country) -> Unit,
    onNumberChange: (String) -> Unit,
    label: String,
    isValid: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPickerOpen by rememberSaveable { mutableStateOf(false) }
    // Set once the SIM turned out not to have its number (or the permission was refused): don't offer it again
    var isOwnNumberUnavailable by rememberSaveable { mutableStateOf(false) }
    val readOwnNumber = rememberOwnPhoneNumberReader { ownNumber ->
        val filled = ownNumber?.let { fillFromOwnNumber(it, onCountryChange, onNumberChange) } ?: false
        isOwnNumberUnavailable = !filled
    }
    val showError = number.isNotBlank() && !isValid

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            CountryButton(country = country, onClick = { isPickerOpen = true })
            Spacer(Modifier.width(HowYouDoinSpacing.sm))
            OutlinedTextField(
                value = number,
                onValueChange = onNumberChange,
                label = { Text(label) },
                singleLine = true,
                isError = showError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                // Lets the autofill service (Google, Samsung Pass, the iOS keyboard) offer a saved number
                modifier = Modifier.weight(1f).semantics { contentType = ContentType.PhoneNumberNational },
            )
        }
        Spacer(Modifier.height(HowYouDoinSpacing.xs))
        Text(
            text = when {
                number.isBlank() -> stringResource(Res.string.phone_hint)
                showError -> stringResource(Res.string.phone_incomplete, country.displayName)
                else -> stringResource(
                    Res.string.phone_linked_as,
                    formatForDisplay(PhoneNumbers.toE164(country, number), country)
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (showError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md),
        )
        if (readOwnNumber != null && number.isBlank()) {
            if (isOwnNumberUnavailable) {
                Text(
                    stringResource(Res.string.phone_own_number_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = HowYouDoinSpacing.md, vertical = HowYouDoinSpacing.xs),
                )
            } else {
                TextButton(onClick = readOwnNumber) {
                    Icon(Icons.Rounded.SimCard, contentDescription = null)
                    Spacer(Modifier.width(HowYouDoinSpacing.xs))
                    Text(stringResource(Res.string.phone_use_own_number))
                }
            }
        }
    }

    if (isPickerOpen) {
        CountryPickerSheet(
            selected = country,
            onSelect = {
                onCountryChange(it)
                isPickerOpen = false
            },
            onDismiss = { isPickerOpen = false },
        )
    }
}

@Composable
private fun CountryButton(country: Country, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 8.dp)
            .height(56.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
            .clickable(
                role = Role.DropdownList,
                onClickLabel = stringResource(Res.string.phone_choose_country),
                onClick = onClick
            )
            .padding(start = 12.dp, end = 4.dp),
    ) {
        Text(country.flag, fontSize = 22.sp)
        Spacer(Modifier.width(6.dp))
        Text("+${country.dialCode}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
    }
}

/**
 * Puts the SIM's own number in the field: an international "+48 600…" also picks its country, a national one keeps
 * the chosen country. False if it has no usable digits.
 */
internal fun fillFromOwnNumber(
    ownNumber: String,
    onCountryChange: (Country) -> Unit,
    onNumberChange: (String) -> Unit,
): Boolean {
    val international = PhoneNumbers.parseInternational(ownNumber)
    val national = international?.second ?: ownNumber.filter(Char::isDigit)
    if (national.isEmpty()) return false
    international?.first?.let(onCountryChange)
    onNumberChange(national)
    return true
}

/** "+48600123456" → "+48 600 123 456": groups of three after the country code, purely cosmetic. */
private fun formatForDisplay(e164: String, country: Country): String {
    val national = e164.removePrefix("+${country.dialCode}")
    return "+${country.dialCode} " + national.chunked(DIGIT_GROUP).joinToString(" ")
}

/** Digits are shown in groups of three for readability. */
private const val DIGIT_GROUP = 3
