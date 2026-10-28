package com.devvalor.howyoudoing.app.settings

import com.devvalor.howyoudoing.settings.domain.BiometricAuthenticator
import com.devvalor.howyoudoing.settings.domain.CrashReport
import com.devvalor.howyoudoing.settings.domain.CrashReports
import com.devvalor.howyoudoing.settings.domain.PinHasher
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/** PBKDF2-HMAC-SHA256 through CommonCrypto, matching Android's. */
@OptIn(ExperimentalForeignApi::class)
class IosPinHasher : PinHasher {
    override fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { salt ->
        salt.usePinned { SecRandomCopyBytes(kSecRandomDefault, salt.size.convert(), it.addressOf(0)) }
    }

    override fun hash(pin: String, salt: ByteArray): ByteArray {
        val derived = ByteArray(KEY_BYTES)
        salt.usePinned { s ->
            derived.usePinned { d ->
                // The password parameter is a C string, which Kotlin passes as UTF-8
                CCKeyDerivationPBKDF(
                    kCCPBKDF2, pin, pin.encodeToByteArray().size.convert(),
                    s.addressOf(0).reinterpret<UByteVar>(), salt.size.convert(),
                    kCCPRFHmacAlgSHA256, 120_000u, d.addressOf(0).reinterpret<UByteVar>(), derived.size.convert()
                )
            }
        }
        return derived
    }
}

/** iOS builds are on hold; Face ID / Touch ID isn't wired yet, so the PIN is used. */
object IosBiometricAuthenticator : BiometricAuthenticator {
    override fun isAvailable() = false
    override suspend fun authenticate(title: String, subtitle: String) = false
}

/** No crash log on iOS yet. */
object IosCrashReports : CrashReports {
    private val none = MutableStateFlow<CrashReport?>(null)
    private val offers = MutableStateFlow(false)
    override fun observeLastReport(): Flow<CrashReport?> = none
    override fun share(report: CrashReport) = Unit
    override suspend fun delete() = Unit
    override fun observeOffersAfterCrash(): Flow<Boolean> = offers
    override suspend fun setOffersAfterCrash(offers: Boolean) {
        this.offers.value = offers
    }
}

// The same sizes as Android's, so a hash means the same on both
private const val SALT_BYTES = 16
private const val KEY_BYTES = 32
