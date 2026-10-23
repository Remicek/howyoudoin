package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.core.domain.auth.AuthInfo

/**
 * End-to-end encryption for one Matrix device (Olm/Megolm). Events go in and out as raw JSON; on Android it's the
 * Go library (mautrix-go crypto). Calls may block, so the engine makes them off the main thread.
 */
interface MatrixCrypto {
    /** Takes a /sync response: room keys sent to this device, device list changes, room state. */
    fun processSync(syncJson: String, since: String?)

    /** The decrypted `{"type": …, "content": …}` of an `m.room.encrypted` event. */
    fun decrypt(roomId: String, eventJson: String): DecryptResult

    /** The `m.room.encrypted` content for an event sent to the room (shares the room key with its members). */
    fun encrypt(roomId: String, type: String, contentJson: String): String

    /** This device is signed with the account's cross-signing key. */
    fun isVerified(): Boolean

    /** Verifies this device with the account's recovery key and restores the key backup; returns the keys restored. */
    fun verifyWithRecoveryKey(recoveryKey: String): Int

    /** Encrypts a file for an encrypted room with a new key (the server only ever gets the ciphertext). */
    fun encryptAttachment(bytes: ByteArray): EncryptedAttachment

    /** Decrypts a downloaded file with its event's `file` object; throws if it was tampered with. */
    fun decryptAttachment(ciphertext: ByteArray, fileJson: String): ByteArray

    fun close()
}

/** Upload [ciphertext]; send [fileJson] plus the upload's `mxc://` URL as `url`, in the event's `file`. */
class EncryptedAttachment(val ciphertext: ByteArray, val fileJson: String)

/** Decrypted media of encrypted rooms, kept as local files the image loader can open. */
interface MatrixMediaCache {
    /** The cached file's path, or null. */
    fun path(mxcUrl: String): String?

    /** Saves decrypted bytes; returns the file's path. */
    fun write(mxcUrl: String, bytes: ByteArray): String

    fun read(mxcUrl: String): ByteArray?
}

sealed interface DecryptResult {
    data class Decrypted(val json: String) : DecryptResult
    /** The room key hasn't arrived (yet); trying again later may work. */
    data object MissingKeys : DecryptResult
    data class Failed(val reason: String) : DecryptResult
}

/**
 * Opens the keys of a session's device; null where encryption isn't available (or the keys can't be opened).
 * Throws [MatrixKeysLostException] when the device's keys exist on the server but not here.
 */
fun interface MatrixCryptoFactory {
    suspend fun open(session: AuthInfo): MatrixCrypto?
}

/** The device's keys are on the server but not on this phone (e.g. a login restored from backup). */
class MatrixKeysLostException(message: String) : Exception(message)
