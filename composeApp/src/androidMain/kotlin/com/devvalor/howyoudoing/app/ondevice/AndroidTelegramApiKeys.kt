package com.devvalor.howyoudoing.app.ondevice

import android.content.Context
import com.devvalor.howyoudoing.app.BuildConfig
import com.devvalor.howyoudoing.core.domain.ondevice.OwnTelegramKeys
import com.devvalor.howyoudoing.core.domain.ondevice.TelegramApiKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The user's own Telegram keys, encrypted with a Keystore key like the network logins. */
class AndroidTelegramApiKeys(context: Context) : TelegramApiKeys {
    private val store = KeystoreSecretStore(context, "telegram_api_keys")
    private val own = MutableStateFlow(read())

    override val isSupported = true

    /** The keys Telegram should use now: the user's, else the app's own; null if neither is there. */
    fun current(): OwnTelegramKeys? = own.value ?: builtInTelegramKeys()

    override fun observeOwn(): Flow<OwnTelegramKeys?> = own.asStateFlow()

    override suspend fun setOwn(keys: OwnTelegramKeys?) {
        withContext(Dispatchers.IO) {
            if (keys == null) store.clear() else store.write("${keys.apiId}:${keys.apiHash}")
        }
        own.value = keys
    }

    private fun read(): OwnTelegramKeys? = runCatching { store.read() }.getOrNull()
        ?.split(":", limit = 2)
        ?.let { TelegramApiKeys.parse(it[0], it.getOrElse(1) { "" }) }
}

/**
 * The app's own keys, XOR-ed in BuildConfig with a pad that isn't stored: it's hashed from a random salt (the same
 * way build.gradle made the masked bytes), so a decompiled APK has only the masked bytes, the salt and this loop.
 */
internal fun builtInTelegramKeys(): OwnTelegramKeys? {
    val masked = BuildConfig.TELEGRAM_KEYS.hexBytes()
    val salt = BuildConfig.TELEGRAM_KEYS_SALT.hexBytes()
    if (masked.isEmpty() || salt.isEmpty()) return null
    val pad = derivePad(salt, masked.size)
    val keys = String(ByteArray(masked.size) { (masked[it].toInt() xor pad[it].toInt()).toByte() })
    return keys.split(":", limit = 2).let { TelegramApiKeys.parse(it[0], it.getOrElse(1) { "" }) }
}

/** The XOR pad: SHA-256 of the salt, chained to [len] bytes. Must match build.gradle's derivePad exactly. */
private fun derivePad(salt: ByteArray, len: Int): ByteArray {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val out = ByteArray(len)
    var block = ByteArray(0)
    var i = 0
    while (i < len) {
        block = digest.digest(salt + block)
        var j = 0
        while (i < len && j < block.size) out[i++] = block[j++]
    }
    return out
}

private const val HEX = 16

private fun String.hexBytes(): ByteArray = chunked(2).mapNotNull { it.toIntOrNull(HEX)?.toByte() }.toByteArray()
