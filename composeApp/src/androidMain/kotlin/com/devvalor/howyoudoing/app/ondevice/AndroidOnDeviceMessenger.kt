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
    override fun sendVoice(
        threadKey: String, isEncrypted: Boolean, isGroup: Boolean, path: String, mimeType: String, durationMs: Long, waveform: String
    ): String = m.sendVoice(threadKey, isEncrypted, isGroup, path, mimeType, durationMs, waveform)
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
class AndroidWhatsAppFactory : NativeLinkedClientFactory {
    override fun create(dataDir: String, onEvent: (String) -> Unit): NativeLinkedClient {
        val w = Hydbridge.newWhatsApp(dataDir, listener(onEvent))
        return object : NativeLinkedClient {
            override fun isLinked() = w.isLinked
            override fun connect() = w.connect("")
            // A phone number asks for a pairing code, none for QR codes
            override fun startLinking(phoneNumber: String) = w.connect(phoneNumber)
            override fun cancelLinking() = w.disconnect()
            override fun disconnect() = w.disconnect()
            override fun logout() = w.logout()
            override fun sendText(chat: String, text: String): String = w.sendText(chat, text)
            override fun sendReaction(chat: String, messageId: String, senderId: String, emoji: String) =
                w.sendReaction(chat, messageId, senderId, emoji)
            override fun markRead(chat: String, messageId: String, senderId: String) = w.markRead(chat, messageId, senderId)
            override fun setTyping(chat: String, isTyping: Boolean) = w.setTyping(chat, isTyping)
            // Older messages come from the user's own phone as an on-demand history sync
            override fun requestOlder(chat: String, beforeMessageId: String, beforeIsOutgoing: Boolean, beforeTimestampMs: Long) =
                w.requestHistory(chat, beforeMessageId, beforeIsOutgoing, beforeTimestampMs, WHATSAPP_HISTORY_PAGE)
            override fun sendMediaFile(
                chat: String, path: String, mimeType: String, fileName: String, caption: String, width: Long, height: Long
            ): String = w.sendMediaFile(chat, path, mimeType, fileName, caption, width, height)
            override fun sendVoice(chat: String, path: String, mimeType: String, durationMs: Long, waveform: String): String =
                w.sendVoice(chat, path, mimeType, durationMs, waveform)
            override fun downloadMedia(chat: String, messageId: String, mediaRef: String): String = w.downloadMedia(chat, messageId, mediaRef)
            override fun fetchAvatars(chats: String) = w.fetchAvatars(chats)
        }
    }

    private companion object {
        const val WHATSAPP_HISTORY_PAGE = 50L
    }
}

/** hydbridge's Telegram client; [apiId] and [apiHash] are the app's credentials from my.telegram.org. */
class AndroidTelegramFactory(private val apiId: Long, private val apiHash: String) : NativeLinkedClientFactory {
    override fun create(dataDir: String, onEvent: (String) -> Unit): NativeLinkedClient {
        val t = Hydbridge.newTelegram(apiId, apiHash, dataDir, listener(onEvent))
        return object : NativeLinkedClient {
            override fun isLinked() = t.isLoggedIn
            override fun connect() = t.connect()
            override fun startLinking(phoneNumber: String) = t.startLogin(phoneNumber)
            override fun submitCode(code: String) = t.submitCode(code)
            override fun submitPassword(password: String) = t.submitPassword(password)
            override fun cancelLinking() {
                t.cancelQRLogin()
                if (!t.isLoggedIn) t.disconnect()
            }
            override fun disconnect() = t.disconnect()
            override fun logout() = t.logout()
            override fun sendText(chat: String, text: String): String = t.sendText(chat, text)
            override fun sendReaction(chat: String, messageId: String, senderId: String, emoji: String) =
                t.sendReaction(chat, messageId, emoji)
            override fun markRead(chat: String, messageId: String, senderId: String) = t.markRead(chat, messageId)
            override fun setTyping(chat: String, isTyping: Boolean) = t.setTyping(chat, isTyping)
            override fun requestOlder(chat: String, beforeMessageId: String, beforeIsOutgoing: Boolean, beforeTimestampMs: Long) =
                t.requestOlder(chat, beforeMessageId)
            override fun sendMediaFile(
                chat: String, path: String, mimeType: String, fileName: String, caption: String, width: Long, height: Long
            ): String = t.sendMediaFile(chat, path, mimeType, fileName, caption, width, height)
            override fun sendVoice(chat: String, path: String, mimeType: String, durationMs: Long, waveform: String): String =
                t.sendVoice(chat, path, mimeType, durationMs, waveform)
            override fun downloadMedia(chat: String, messageId: String, mediaRef: String): String = t.downloadMedia(chat, messageId, mediaRef)
            override fun fetchAvatars(chats: String) = t.fetchAvatars(chats)
        }
    }
}

/** hydbridge's Signal client (linked as a secondary device). */
class AndroidSignalFactory : NativeLinkedClientFactory {
    override fun create(dataDir: String, onEvent: (String) -> Unit): NativeLinkedClient {
        val s = Hydbridge.newSignal(dataDir, listener(onEvent))
        return object : NativeLinkedClient {
            override fun isLinked() = s.isLinked
            override fun connect() = s.connect()
            override fun startLinking(phoneNumber: String) = s.link()
            override fun cancelLinking() = s.cancelLink()
            override fun disconnect() = s.disconnect()
            override fun logout() = s.logout()
            override fun sendText(chat: String, text: String): String = s.sendText(chat, text)
            override fun sendReaction(chat: String, messageId: String, senderId: String, emoji: String) =
                s.sendReaction(chat, messageId, emoji)
            override fun markRead(chat: String, messageId: String, senderId: String) = s.markRead(chat, messageId)
            override fun setTyping(chat: String, isTyping: Boolean) = s.setTyping(chat, isTyping)
            override fun requestOlder(chat: String, beforeMessageId: String, beforeIsOutgoing: Boolean, beforeTimestampMs: Long) =
                s.requestOlder(chat, beforeTimestampMs)
            override fun sendMediaFile(
                chat: String, path: String, mimeType: String, fileName: String, caption: String, width: Long, height: Long
            ): String = s.sendMediaFile(chat, path, mimeType, fileName, caption, width, height)
            override fun sendVoice(chat: String, path: String, mimeType: String, durationMs: Long, waveform: String): String =
                s.sendVoice(chat, path, mimeType, durationMs, waveform)
            override fun downloadMedia(chat: String, messageId: String, mediaRef: String): String = s.downloadMedia(chat, messageId, mediaRef)
        }
    }
}

private fun listener(onEvent: (String) -> Unit) = object : Listener {
    override fun onEvent(json: String) = onEvent(json)
}

/** hydbridge's Instagram client from the gomobile AAR. Instagram has no encrypted chats or history backup. */
class AndroidInstagramFactory : NativeMessengerFactory {
    override fun create(cookiesJson: String, dataDir: String, onEvent: (String) -> Unit): NativeMessengerClient {
        val ig = Hydbridge.newInstagram(cookiesJson, listener(onEvent))
        return object : NativeMessengerClient {
            override fun connect() = ig.connect()
            override fun disconnect() = ig.disconnect()
            override fun sendText(threadKey: String, text: String): String = ig.sendText(threadKey, text)
            override fun sendEncryptedText(threadKey: String, isGroup: Boolean, text: String): String = unsupported()
            override fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String) = ig.requestOlder(threadKey, beforeMessageId)
            override fun historyStatus(): String = unsupported()
            override fun restoreHistory(pin: String) = unsupported()
            override fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long, count: Long) = unsupported()
            override fun sendReaction(threadKey: String, messageId: String, emoji: String) = ig.sendReaction(threadKey, messageId, emoji)
            override fun sendEncryptedReaction(threadKey: String, isGroup: Boolean, messageId: String, messageSenderId: String, emoji: String) =
                unsupported()
            override fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String) =
                ig.markRead(threadKey, lastMessageId)
            override fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean) = ig.setTyping(threadKey, isTyping)
            override fun sendMediaFile(
                threadKey: String, isEncrypted: Boolean, isGroup: Boolean, path: String, mimeType: String, fileName: String,
                caption: String, width: Long, height: Long
            ): String = ig.sendMediaFile(threadKey, path, mimeType, fileName, caption)
            override fun sendVoice(
                threadKey: String, isEncrypted: Boolean, isGroup: Boolean, path: String, mimeType: String, durationMs: Long, waveform: String
            ): String = ig.sendVoice(threadKey, path, mimeType, durationMs, waveform)
        }
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Instagram has no encrypted chats")
}
