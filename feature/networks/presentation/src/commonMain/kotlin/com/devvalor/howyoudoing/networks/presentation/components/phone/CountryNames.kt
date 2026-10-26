package com.devvalor.howyoudoing.networks.presentation.components.phone

/** The country's name in the phone's current language, or null if the platform doesn't know it. */
internal expect fun localizedCountryName(isoCode: String): String?
