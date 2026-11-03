package com.devvalor.howyoudoing.networks.presentation.components.phone

import androidx.compose.runtime.Composable

/**
 * Fills in this phone's own number: on Android Google's phone number picker, else the SIM's number (asking for the
 * permission once). Passes the number to [onResult], or null if there's none to offer or the permission is refused;
 * nothing if the user dismisses the picker. Returns the function that starts it, or null where it isn't available.
 */
@Composable
internal expect fun rememberOwnPhoneNumberReader(onResult: (String?) -> Unit): (() -> Unit)?
