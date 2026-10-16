package com.devvalor.howyoudoing.networks.presentation.components.phone

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Prefers the SIM, then the mobile network, then the language region: a phone set to English (US)
 * with a Polish SIM should still default to +48.
 */
class AndroidDeviceRegionProvider(
    private val context: Context
) : DeviceRegionProvider {

    override fun countryIso(): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return listOfNotNull(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
            .map { it.trim().uppercase() }
            .firstOrNull { it.length == 2 }
    }
}
