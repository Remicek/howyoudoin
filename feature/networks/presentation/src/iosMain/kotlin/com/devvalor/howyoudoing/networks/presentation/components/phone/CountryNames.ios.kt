package com.devvalor.howyoudoing.networks.presentation.components.phone

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localizedStringForCountryCode

internal actual fun localizedCountryName(isoCode: String): String? =
    NSLocale.currentLocale.localizedStringForCountryCode(isoCode)
