package com.devvalor.howyoudoing.app.settings

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.devvalor.howyoudoing.app.CrashLog
import com.devvalor.howyoudoing.settings.domain.BiometricAuthenticator
import com.devvalor.howyoudoing.settings.domain.CrashReport
import com.devvalor.howyoudoing.settings.domain.CrashReports
import com.devvalor.howyoudoing.settings.domain.PinHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.coroutines.resume

/** PBKDF2-HMAC-SHA256: slow enough that the stored hash doesn't give a short PIN away. */
class AndroidPinHasher : PinHasher {
    override fun newSalt(): ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)

    override fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val ITERATIONS = 120_000
    }
}

/** The activity on screen, for the fingerprint prompt (which needs one). Set by MainActivity. */
object CurrentActivity {
    private var ref = WeakReference<FragmentActivity>(null)
    var activity: FragmentActivity?
        get() = ref.get()
        set(value) {
            ref = WeakReference(value)
        }
}

class AndroidBiometricAuthenticator(private val context: Context) : BiometricAuthenticator {

    override fun isAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun authenticate(title: String, subtitle: String): Boolean = withContext(Dispatchers.Main) {
        val activity = CurrentActivity.activity ?: return@withContext false
        if (!isAvailable()) return@withContext false
        suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                    // onAuthenticationFailed: a finger wasn't recognised; the prompt stays open for another try
                }
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setNegativeButtonText("Use PIN")
                    .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
                    .build()
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}

/** The crash report [CrashLog] keeps; the "offer after a crash" choice is read by CrashLog at start. */
class AndroidCrashReports(private val context: Context) : CrashReports {

    private val lastReport = MutableStateFlow(CrashLog.lastReport(context)?.let { (time, text) -> CrashReport(time, text) })
    private val offers = MutableStateFlow(CrashLog.offersAfterCrash(context))

    override fun observeLastReport(): Flow<CrashReport?> = lastReport.asStateFlow()

    override fun share(report: CrashReport) {
        CrashLog.share(context, report.text)
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) { CrashLog.deleteReport(context) }
        lastReport.value = null
    }

    override fun observeOffersAfterCrash(): Flow<Boolean> = offers.asStateFlow()

    override suspend fun setOffersAfterCrash(offers: Boolean) {
        CrashLog.setOffersAfterCrash(context, offers)
        this.offers.value = offers
    }
}
