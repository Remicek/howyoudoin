package com.devvalor.howyoudoing.core.data.auth

/**
 * A no-op [SecretCipher] for platforms without a wired-up secure store. iOS is not shipped; when it is,
 * replace this with a Keychain-backed implementation so the stored session is encrypted there too.
 */
class PlaintextSecretCipher : SecretCipher {
    override fun encrypt(plain: String): String = plain
    override fun decrypt(encoded: String): String? = encoded
}
