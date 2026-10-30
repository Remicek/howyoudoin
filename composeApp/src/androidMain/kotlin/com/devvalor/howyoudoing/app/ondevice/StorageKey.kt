package com.devvalor.howyoudoing.app.ondevice

import android.content.Context
import android.util.Base64
import android.util.Log
import com.devvalor.howyoudoing.bridge.hydbridge.Hydbridge
import java.security.SecureRandom

/**
 * Hands the network clients library the key its login files (sessions, cookies, encryption keys) are encrypted with.
 * The key is random, made once, and kept encrypted with a Keystore key like the logins the app keeps itself. Call it
 * before any client is created.
 */
object StorageKey {
    private const val SIZE = 32
    private const val TAG = "StorageKey"

    fun install(context: Context) {
        val store = KeystoreSecretStore(context, "hydbridge_storage_key")
        val key = runCatching { store.read() }.getOrNull()
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf { it.size == SIZE }
            ?: ByteArray(SIZE).also {
                // A lost key (the Keystore key gone) leaves earlier login files unreadable: those networks log in again
                SecureRandom().nextBytes(it)
                store.write(Base64.encodeToString(it, Base64.NO_WRAP))
            }
        runCatching { Hydbridge.setStorageKey(key) }.onFailure { Log.e(TAG, "Storage key not set: ${it.message}") }
    }
}
