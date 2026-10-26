package com.devvalor.howyoudoing.networks.presentation.components

import howyoudoin.feature.networks.presentation.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import howyoudoin.feature.networks.presentation.generated.resources.qrcode_login_qr_code
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

@Composable
fun QrCode(
    data: String,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
) {
    // Always dark-on-white so phones can scan it in dark mode too.
    Box(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Image(
            painter = rememberQrCodePainter(data),
            contentDescription = stringResource(Res.string.qrcode_login_qr_code),
            modifier = Modifier.size(size),
        )
    }
}
