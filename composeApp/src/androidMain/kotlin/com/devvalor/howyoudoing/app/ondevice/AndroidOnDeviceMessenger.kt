package com.devvalor.howyoudoing.app.ondevice

import android.content.Context
import com.devvalor.howyoudoing.bridge.hydbridge.Hydbridge
import com.devvalor.howyoudoing.bridge.hydbridge.Listener
import com.devvalor.howyoudoing.bridge.hydbridge.Messenger
import java.io.File

/** hydbridge from the gomobile AAR (composeApp/libs/hydbridge.aar). */
class AndroidMessengerFactory : NativeMessengerFactory {
    override fun create(cookiesJson: String, dataDir: String, onEvent: (String) -> Unit): NativeMessengerClient {
        val listener = object : Listener {
            override fun onEvent(json: String) = onEvent(json)
        }
        return AndroidMessengerClient(Hydbridge.newMessenger(cookiesJson, dataDir, listener))
    }
}

private class AndroidMessengerClient(private val m: Messenger) : NativeMessengerClient {
    override fun connect() = m.connect()
    override fun disconnect() = m.disconnect()
    override fun sendText(threadKey: String, text: String): String = m.sendText(threadKey, text)
    override fun sendEncryptedText(threadKey: String, isGroup: Boolean, text: String): String = m.sendEncryptedText(threadKey, isGroup, text)
    override fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String) =
        m.requestOlder(threadKey, beforeTimestampMs, beforeMessageId)
    override fun historyStatus(): String = m.historyStatus()
    override fun restoreHistory(pin: String) = m.restoreHistory(pin)
    override fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long, count: Long) =
        m.fetchEncryptedHistory(threadKey, isGroup, beforeTimestampMs, count)
    override fun sendReaction(threadKey: String, messageId: String, emoji: String) = m.sendReaction(threadKey, messageId, emoji)
    override fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String) =
        m.markRead(threadKey, isEncrypted, isGroup, lastMessageId, senderId)
    override fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean) =
        m.setTyping(threadKey, isEncrypted, isGroup, isTyping)
}

/** Cookies encrypted with a Keystore key in private SharedPreferences (plain copies from early builds migrate). */
class KeystoreSecretStore(context: Context) : OnDeviceSecretStore {
    private val prefs = context.getSharedPreferences("on_device_messenger", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("howyoudoin_on_device_messenger")

    override fun read(): String? {
        prefs.getString(KEY_ENCRYPTED, null)?.let { return cipher.decrypt(it) }
        return prefs.getString(KEY_PLAIN, null)?.also(::write)
    }

    override fun write(value: String) {
        prefs.edit().putString(KEY_ENCRYPTED, cipher.encrypt(value)).remove(KEY_PLAIN).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_ENCRYPTED).remove(KEY_PLAIN).apply()
    }

    private companion object {
        const val KEY_PLAIN = "cookies"
        const val KEY_ENCRYPTED = "cookies_encrypted"
    }
}

/** Not backed up, so encrypted-chat keys never leave the phone. */
class AndroidDataDirectory(context: Context) : OnDeviceDataDirectory {
    private val dir = File(context.noBackupFilesDir, "on_device_messenger").apply { mkdirs() }
    override val path: String get() = dir.absolutePath
    override fun clear() {
        dir.deleteRecursively()
        dir.mkdirs()
    }
}
