package com.devvalor.howyoudoing.app.captcha

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.devvalor.howyoudoing.core.domain.ondevice.CaptchaChallenge
import com.devvalor.howyoudoing.core.domain.ondevice.CaptchaSolver
import howyoudoin.composeapp.generated.resources.Res
import howyoudoin.composeapp.generated.resources.captcha_cancel
import howyoudoin.composeapp.generated.resources.captcha_text
import howyoudoin.composeapp.generated.resources.captcha_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Shows the CAPTCHA a network asked for (e.g. Discord, for a friend request); what it was for goes on once solved. */
@Composable
fun CaptchaRoot(solver: CaptchaSolver = koinInject()) {
    val challenge by solver.pending.collectAsStateWithLifecycle()
    val current = challenge ?: return
    AlertDialog(
        onDismissRequest = { solver.submit(null) },
        title = { Text(stringResource(Res.string.captcha_title, current.network.displayName)) },
        text = {
            Column {
                Text(stringResource(Res.string.captcha_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                CaptchaWebView(
                    challenge = current,
                    onSolved = solver::submit,
                    modifier = Modifier.fillMaxWidth().height(CAPTCHA_HEIGHT.dp)
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { solver.submit(null) }) { Text(stringResource(Res.string.captcha_cancel)) }
        },
    )
}

/** The CAPTCHA itself, on a page that passes for [CaptchaChallenge.pageUrl]; [onSolved] gets its token. */
@Composable
expect fun CaptchaWebView(challenge: CaptchaChallenge, onSolved: (String) -> Unit, modifier: Modifier = Modifier)

// Tall enough for hCaptcha's picture grid
private const val CAPTCHA_HEIGHT = 520

/** A page with just the CAPTCHA, which calls `HydCaptcha.onSolved(token)` when it's solved. */
internal fun captchaPage(challenge: CaptchaChallenge): String {
    fun js(value: String?) = value?.let { kotlinx.serialization.json.JsonPrimitive(it).toString() } ?: "null"
    return """
<!DOCTYPE html>
<html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<script src="https://js.hcaptcha.com/1/api.js?onload=hydStart&render=explicit" async defer></script>
</head>
<body style="margin:0;display:flex;justify-content:center;padding-top:8px;background:transparent">
<div id="captcha"></div>
<script>
function hydStart() {
  var id = hcaptcha.render('captcha', {
    sitekey: ${js(challenge.siteKey)},
    callback: function(token) { HydCaptcha.onSolved(token); }
  });
  var rqdata = ${js(challenge.rqData)};
  if (rqdata) hcaptcha.setData(id, { rqdata: rqdata });
}
</script>
</body></html>
""".trimIndent()
}
