// Calls into the Go library (hydbridge) fail with plain Exceptions carrying Go's error text, so they're caught as such
@file:Suppress("TooGenericExceptionCaught")

package com.devvalor.howyoudoing.app.ondevice

import android.content.Context
import android.util.Base64
import android.util.Log
import com.devvalor.howyoudoing.bridge.hydbridge.Hydbridge
import com.devvalor.howyoudoing.bridge.hydbridge.MatrixCrypto as GoCrypto
import com.devvalor.howyoudoing.chat.data.matrix.DecryptResult
import com.devvalor.howyoudoing.chat.data.matrix.EncryptedAttachment
import com.devvalor.howyoudoing.chat.data.matrix.MatrixMediaCache
import com.devvalor.howyoudoing.chat.data.matrix.MatrixCrypto
import com.devvalor.howyoudoing.chat.data.matrix.MatrixCryptoFactory
import com.devvalor.howyoudoing.chat.data.matrix.MatrixKeysLostException
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/** Matrix encryption by the Go library (mautrix-go crypto with pure-Go olm). */
class GoMatrixCrypto(private val crypto: GoCrypto) : MatrixCrypto {
    override fun processSync(syncJson: String, since: String?) = crypto.processSync(syncJson, since.orEmpty())

    override fun decrypt(roomId: String, eventJson: String): DecryptResult = try {
        DecryptResult.Decrypted(crypto.decrypt(roomId, eventJson))
    } catch (e: Exception) {
        val message = e.message.orEmpty()
        if (message.startsWith(Hydbridge.MissingKeysPrefix)) {
            DecryptResult.MissingKeys
        } else {
            DecryptResult.Failed(message)
        }
    }

    override fun encrypt(roomId: String, type: String, contentJson: String): String =
        crypto.encrypt(roomId, type, contentJson)

    override fun isVerified(): Boolean = crypto.isVerified
    override fun verifyWithRecoveryKey(recoveryKey: String): Int = crypto.verifyWithRecoveryKey(recoveryKey).toInt()

    override fun encryptAttachment(bytes: ByteArray): EncryptedAttachment =
        Hydbridge.encryptAttachment(bytes).let { EncryptedAttachment(it.ciphertext, it.fileJSON) }

    override fun decryptAttachment(ciphertext: ByteArray, fileJson: String): ByteArray =
        Hydbridge.decryptAttachment(ciphertext, fileJson)

    override fun close() = crypto.close()
}

/**
 * Keys in no-backup storage (they must never come back on another install: the server would see a different
 * device with the same ID), one database per device, encrypted with a random key kept in the Keystore.
 */
class GoMatrixCryptoFactory(context: Context) : MatrixCryptoFactory {
    private val dir = File(context.noBackupFilesDir, "matrix-crypto")
    private val pickleKeyStore = KeystoreSecretStore(context, "matrix_crypto")

    override suspend fun open(session: AuthInfo): MatrixCrypto? = withContext(Dispatchers.IO) {
        try {
            dir.mkdirs()
            val db = File(dir, "${session.deviceId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.db")
            GoMatrixCrypto(
                Hydbridge.newMatrixCrypto(
                    session.homeserverUrl,
                    session.userId,
                    session.deviceId,
                    session.accessToken,
                    db.absolutePath,
                    pickleKey()
                )
            )
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            // mautrix: the server has keys for this device that this database doesn't (or they vanished there)
            if ("keys on the server" in message || "keys seem to have disappeared" in message) {
                throw MatrixKeysLostException(message)
            }
            Log.w(TAG, "Matrix encryption unavailable: $message")
            null
        }
    }

    private fun pickleKey(): String = pickleKeyStore.read() ?: ByteArray(PICKLE_KEY_BYTES)
        .also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.NO_WRAP) }
        .also(pickleKeyStore::write)

    private companion object {
        const val TAG = "MatrixCrypto"
    }
}

/**
 * Decrypted photos and files of encrypted Matrix rooms, in the cache directory (Android may clear it; they're then
 * downloaded and decrypted again). Not backed up.
 */
class AndroidMatrixMediaCache(context: Context) : MatrixMediaCache {
    private val dir = File(context.cacheDir, "matrix-media")

    private fun file(mxcUrl: String) = File(dir, mxcUrl.removePrefix("mxc://").replace(Regex("[^A-Za-z0-9._-]"), "_"))

    override fun path(mxcUrl: String): String? = file(mxcUrl).takeIf { it.isFile }?.absolutePath

    override fun write(mxcUrl: String, bytes: ByteArray): String {
        dir.mkdirs()
        val target = file(mxcUrl)
        val part = File(dir, target.name + ".part")
        part.writeBytes(bytes)
        part.renameTo(target)
        return target.absolutePath
    }

    override fun read(mxcUrl: String): ByteArray? = file(mxcUrl).takeIf { it.isFile }?.readBytes()
}

/** An empty key for databases created before the pickle key was stored. */
private const val PICKLE_KEY_BYTES = 32
