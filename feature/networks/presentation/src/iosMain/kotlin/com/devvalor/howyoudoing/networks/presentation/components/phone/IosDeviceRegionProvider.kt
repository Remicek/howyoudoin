package com.devvalor.howyoudoing.networks.presentation.components.phone

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale

/** iOS no longer exposes the carrier country (CTCarrier is deprecated), so use the region setting. */
class IosDeviceRegionProvider : DeviceRegionProvider {
    override fun countryIso(): String? = NSLocale.currentLocale.countryCode
}
