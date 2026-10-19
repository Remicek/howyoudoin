package com.devvalor.howyoudoing.app.ondevice

import android.content.Context
import com.devvalor.howyoudoing.bridge.hydbridge.Hydbridge
import com.devvalor.howyoudoing.bridge.hydbridge.Listener
import com.devvalor.howyoudoing.bridge.hydbridge.Messenger
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceInboxCache
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
    override fun sendMediaFile(
        threadKey: String, isEncrypted: Boolean, isGroup: Boolean, path: String, mimeType: String, fileName: String,
        caption: String, width: Long, height: Long
    ): String = m.sendMediaFile(threadKey, isEncrypted, isGroup, path, mimeType, fileName, caption, width, height)
    override fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String) =
        m.requestOlder(threadKey, beforeTimestampMs, beforeMessageId)
    override fun historyStatus(): String = m.historyStatus()
    override fun restoreHistory(pin: String) = m.restoreHistory(pin)
    override fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long, count: Long) =
        m.fetchEncryptedHistory(threadKey, isGroup, beforeTimestampMs, count)
    override fun sendReaction(threadKey: String, messageId: String, emoji: String) = m.sendReaction(threadKey, messageId, emoji)
    override fun sendEncryptedReaction(threadKey: String, isGroup: Boolean, messageId: String, messageSenderId: String, emoji: String) =
        m.sendEncryptedReaction(threadKey, isGroup, messageId, messageSenderId, emoji)
    override fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String) =
        m.markRead(threadKey, isEncrypted, isGroup, lastMessageId, senderId)
    override fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean) =
        m.setTyping(threadKey, isEncrypted, isGroup, isTyping)
    override fun fetchAvatars(contactIds: String) = m.fetchAvatars(contactIds)
}

/** Cookies encrypted with a Keystore key in private SharedPreferences (plain copies from early builds migrate). */
class KeystoreSecretStore(context: Context, name: String = "on_device_messenger") : OnDeviceSecretStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("howyoudoin_$name")

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
class AndroidDataDirectory(context: Context, name: String = "on_device_messenger") : OnDeviceDataDirectory {
    private val dir = File(context.noBackupFilesDir, name).apply { mkdirs() }
    override val path: String get() = dir.absolutePath
    override fun clear() {
        dir.deleteRecursively()
        dir.mkdirs()
    }
    override fun writeTemp(bytes: ByteArray): String =
        File.createTempFile("outgoing", null, dir.also { it.mkdirs() }).apply { writeBytes(bytes) }.absolutePath
    override fun read(path: String): ByteArray? {
        val file = File(path).canonicalFile
        return file.takeIf { it.path.startsWith(dir.canonicalPath + File.separator) && it.isFile }?.readBytes()
    }
}

/** The on-device inbox cache, next to the encrypted-chat keys (cleared with them on Disconnect). */
class AndroidInboxCache(context: Context, directory: String = "on_device_messenger") : OnDeviceInboxCache {
    private val file = File(File(context.noBackupFilesDir, directory).apply { mkdirs() }, "inbox.json")
    override fun read(): String? = file.takeIf { it.exists() }?.readText()
    override fun write(value: String) {
        val tmp = File(file.parentFile, "inbox.json.tmp")
        tmp.writeText(value)
        tmp.renameTo(file)
    }
    override fun clear() {
        file.delete()
    }
}

/** hydbridge's WhatsApp client from the gomobile AAR. */
class AndroidWhatsAppFactory : NativeWhatsAppFactory {
    override fun create(dataDir: String, onEvent: (String) -> Unit): NativeWhatsAppClient {
        val listener = object : Listener {
            override fun onEvent(json: String) = onEvent(json)
        }
        val w = Hydbridge.newWhatsApp(dataDir, listener)
        return object : NativeWhatsAppClient {
            override fun isLinked() = w.isLinked
            override fun connect(phoneNumber: String) = w.connect(phoneNumber)
            override fun disconnect() = w.disconnect()
            override fun logout() = w.logout()
            override fun sendText(chatJid: String, text: String): String = w.sendText(chatJid, text)
            override fun sendReaction(chatJid: String, messageId: String, senderJid: String, emoji: String) =
                w.sendReaction(chatJid, messageId, senderJid, emoji)
            override fun markRead(chatJid: String, messageId: String, senderJid: String) = w.markRead(chatJid, messageId, senderJid)
            override fun setTyping(chatJid: String, isTyping: Boolean) = w.setTyping(chatJid, isTyping)
            override fun requestHistory(chatJid: String, oldestMessageId: String, oldestIsOutgoing: Boolean, oldestTimestampMs: Long, count: Long) =
                w.requestHistory(chatJid, oldestMessageId, oldestIsOutgoing, oldestTimestampMs, count)
            override fun sendMediaFile(
                chatJid: String, path: String, mimeType: String, fileName: String, caption: String, width: Long, height: Long
            ): String = w.sendMediaFile(chatJid, path, mimeType, fileName, caption, width, height)
            override fun downloadMedia(chatJid: String, messageId: String, mediaRef: String): String =
                w.downloadMedia(chatJid, messageId, mediaRef)
            override fun fetchAvatars(chatJids: String) = w.fetchAvatars(chatJids)
        }
    }
}

/** hydbridge's Instagram client from the gomobile AAR; only connecting and text are wired so far. */
class AndroidInstagramFactory : NativeMessengerFactory {
    override fun create(cookiesJson: String, dataDir: String, onEvent: (String) -> Unit): NativeMessengerClient {
        val listener = object : Listener {
            override fun onEvent(json: String) = onEvent(json)
        }
        val ig = Hydbridge.newInstagram(cookiesJson, listener)
        return object : NativeMessengerClient {
            override fun connect() = ig.connect()
            override fun disconnect() = ig.disconnect()
            override fun sendText(threadKey: String, text: String): String = ig.sendText(threadKey, text)
            override fun sendEncryptedText(threadKey: String, isGroup: Boolean, text: String): String = unsupported()
            override fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String) = ig.requestOlder(threadKey, beforeMessageId)
            override fun historyStatus(): String = unsupported()
            override fun restoreHistory(pin: String) = unsupported()
            override fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long, count: Long) = unsupported()
            override fun sendReaction(threadKey: String, messageId: String, emoji: String) = unsupported()
            override fun sendEncryptedReaction(threadKey: String, isGroup: Boolean, messageId: String, messageSenderId: String, emoji: String) =
                unsupported()
            override fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String) = unsupported()
            override fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean) = unsupported()
        }
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Not supported for Instagram yet")
}

