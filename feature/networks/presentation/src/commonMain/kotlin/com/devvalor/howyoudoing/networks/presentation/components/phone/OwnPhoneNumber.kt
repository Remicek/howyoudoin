package com.devvalor.howyoudoing.networks.presentation.components.phone

import androidx.compose.runtime.Composable

/**
 * Reads this phone's own number from its SIM, asking for the permission first when needed, and passes it (or null if
 * the permission is refused or the SIM doesn't store its number, which is common) to [onResult]. Returns the function
 * that starts the read, or null where there's no SIM to read (no telephony, or a platform without the API).
 */
@Composable
internal expect fun rememberOwnPhoneNumberReader(onResult: (String?) -> Unit): (() -> Unit)?
