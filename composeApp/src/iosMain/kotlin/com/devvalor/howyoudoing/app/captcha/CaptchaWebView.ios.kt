package com.devvalor.howyoudoing.app.captcha

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.devvalor.howyoudoing.core.domain.ondevice.CaptchaChallenge

/** The networks that ask for a CAPTCHA (Discord) only connect on Android so far. */
@Composable
actual fun CaptchaWebView(challenge: CaptchaChallenge, onSolved: (String) -> Unit, modifier: Modifier) = Unit
