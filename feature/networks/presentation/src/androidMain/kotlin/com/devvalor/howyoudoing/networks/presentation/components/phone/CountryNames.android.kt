package com.devvalor.howyoudoing.networks.presentation.components.phone

import java.util.Locale

internal actual fun localizedCountryName(isoCode: String): String? =
    Locale.Builder().setRegion(isoCode).build().getDisplayCountry(Locale.getDefault()).takeIf { it.isNotBlank() && it != isoCode }
