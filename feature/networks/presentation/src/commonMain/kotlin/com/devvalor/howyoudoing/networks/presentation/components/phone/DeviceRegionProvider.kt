package com.devvalor.howyoudoing.networks.presentation.components.phone

/** Best guess of the user's country for phone numbers (ISO 3166-1 alpha-2), e.g. "PL". */
fun interface DeviceRegionProvider {
    fun countryIso(): String?
}
