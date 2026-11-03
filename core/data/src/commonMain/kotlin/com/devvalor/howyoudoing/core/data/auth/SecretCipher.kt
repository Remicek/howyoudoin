package com.devvalor.howyoudoing.core.data.auth

/**
 * Encrypts small secrets kept at rest (the stored session, which holds the access token). Platform
 * implementations bind the key to the device — on Android to the Keystore — so the ciphertext is
 * useless if copied off the device. The stored session is not part of any backup, so a device-bound
 * key is safe here: a wiped key just means the user signs in again.
 */
interface SecretCipher {
    fun encrypt(plain: String): String

    /** Null when [encoded] can't be decrypted (wrong/wiped key, or a value this cipher didn't write). */
    fun decrypt(encoded: String): String?
}
