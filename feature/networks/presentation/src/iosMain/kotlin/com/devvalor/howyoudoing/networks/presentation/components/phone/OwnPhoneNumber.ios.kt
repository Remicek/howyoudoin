package com.devvalor.howyoudoing.networks.presentation.components.phone

import androidx.compose.runtime.Composable

/** iOS doesn't let apps read the phone's own number; the field's autofill hint offers it instead. */
@Composable
internal actual fun rememberOwnPhoneNumberReader(onResult: (String?) -> Unit): (() -> Unit)? = null
