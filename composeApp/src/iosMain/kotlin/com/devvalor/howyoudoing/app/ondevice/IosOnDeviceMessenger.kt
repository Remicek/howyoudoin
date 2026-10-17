@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.devvalor.howyoudoing.app.ondevice

import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceInboxCache
import hydbridge.hyd_call
import hydbridge.hyd_free
import hydbridge.hyd_new
import hydbridge.hyd_new_instagram
import hydbridge.hyd_new_whatsapp
import hydbridge.hyd_release
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.CPointer
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionComplete
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile

/** hydbridge as a static C library (bridge-mobile/cmd/hydbridgec), called by method name with JSON arguments. */
class IosMessengerFactory : NativeMessengerFactory {
    override fun create(cookiesJson: String, dataDir: String, onEvent: (String) -> Unit): NativeMessengerClient {
        val callback = StableRef.create(onEvent)
        val handle = memScoped {
            val error = alloc<CPointerVar<ByteVar>>()
            val handle = hyd_new(cookiesJson.cstr, dataDir.cstr, eventCallback, callback.asCPointer(), error.ptr)
            error.value?.let {
                val message = it.toKString()
                hyd_free(it)
                callback.dispose()
                throw IllegalStateException(message)
            }
            handle
        }
        return IosMessengerClient(handle, callback)
    }
}

/** Go calls this from its own threads with each event. */
private val eventCallback = staticCFunction { context: COpaquePointer?, json: CPointer<ByteVar>? ->
    val onEvent = context?.asStableRef<(String) -> Unit>()?.get() ?: return@staticCFunction
    json?.toKString()?.let(onEvent)
}

private class IosMessengerClient(
    private val handle: Long,
    private val callback: StableRef<(String) -> Unit>
) : NativeMessengerClient {

    override fun connect() {
        call("connect")
    }

    override fun disconnect() {
        call("disconnect")
        hyd_release(handle)
        callback.dispose()
    }

    override fun sendText(threadKey: String, text: String): String =
        call("sendText", "threadKey" to threadKey, "text" to text)

    override fun sendEncryptedText(threadKey: String, isGroup: Boolean, text: String): String =
        call("sendEncryptedText", "threadKey" to threadKey, "isGroup" to isGroup, "text" to text)

    override fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String) {
        call("requestOlder", "threadKey" to threadKey, "beforeTimestampMs" to beforeTimestampMs, "beforeMessageId" to beforeMessageId)
    }

    override fun historyStatus(): String = call("historyStatus")

    override fun restoreHistory(pin: String) {
        call("restoreHistory", "pin" to pin)
    }

    override fun fetchEncryptedHistory(threadKey: String, isGroup: Boolean, beforeTimestampMs: Long, count: Long) {
        call("fetchEncryptedHistory", "threadKey" to threadKey, "isGroup" to isGroup, "beforeTimestampMs" to beforeTimestampMs, "count" to count)
    }

    override fun sendReaction(threadKey: String, messageId: String, emoji: String) {
        call("sendReaction", "threadKey" to threadKey, "messageId" to messageId, "emoji" to emoji)
    }

    override fun sendEncryptedReaction(threadKey: String, isGroup: Boolean, messageId: String, messageSenderId: String, emoji: String) {
        call(
            "sendEncryptedReaction",
            "threadKey" to threadKey, "isGroup" to isGroup, "messageId" to messageId, "senderId" to messageSenderId, "emoji" to emoji
        )
    }

    override fun markRead(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, lastMessageId: String, senderId: String) {
        call("markRead", "threadKey" to threadKey, "isEncrypted" to isEncrypted, "isGroup" to isGroup, "messageId" to lastMessageId, "senderId" to senderId)
    }

    override fun setTyping(threadKey: String, isEncrypted: Boolean, isGroup: Boolean, isTyping: Boolean) {
        call("setTyping", "threadKey" to threadKey, "isEncrypted" to isEncrypted, "isGroup" to isGroup, "isTyping" to isTyping)
    }

    private fun call(method: String, vararg args: Pair<String, Any>): String = memScoped {
        val json = JsonObject(args.associate { (key, value) ->
            key to when (value) {
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        }).toString()
        val error = alloc<CPointerVar<ByteVar>>()
        val result = hyd_call(handle, method.cstr, json.cstr, error.ptr)
        error.value?.let {
            val message = it.toKString()
            hyd_free(it)
            throw IllegalStateException(message)
        }
        result?.let { pointer -> pointer.toKString().also { hyd_free(pointer) } }.orEmpty()
    }
}

private fun appSupportDir(name: String): String {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    return "$base/$name"
}

/** A file under Application Support, encrypted by iOS Data Protection while the phone is locked. */
class IosSecretStore(private val directory: String = "on_device_messenger") : OnDeviceSecretStore {
    private val path get() = "${IosDataDirectory(directory).path}/session.json"

    override fun read(): String? =
        NSString.create(contentsOfFile = path, encoding = NSUTF8StringEncoding, error = null)?.toString()

    override fun write(value: String) {
        val data: NSData = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        data.writeToFile(path, options = NSDataWritingAtomic or NSDataWritingFileProtectionComplete, error = null)
    }

    override fun clear() {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}

/** Excluded from iCloud backups, so encrypted-chat keys stay on this phone. */
class IosDataDirectory(name: String = "on_device_messenger") : OnDeviceDataDirectory {
    override val path: String = appSupportDir(name).also(::ensure)

    override fun clear() {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        ensure(path)
    }

    private fun ensure(dir: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        NSURL.fileURLWithPath(dir).setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
    }
}

/** The on-device inbox cache, protected by iOS Data Protection and excluded from backups with its directory. */
class IosInboxCache(private val directory: String = "on_device_messenger") : OnDeviceInboxCache {
    private val path get() = "${IosDataDirectory(directory).path}/inbox.json"

    override fun read(): String? =
        NSString.create(contentsOfFile = path, encoding = NSUTF8StringEncoding, error = null)?.toString()

    override fun write(value: String) {
        val data: NSData = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        data.writeToFile(path, options = NSDataWritingAtomic or NSDataWritingFileProtectionComplete, error = null)
    }

    override fun clear() {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}

/** hydbridge's WhatsApp client through the static C library. */
class IosWhatsAppFactory : NativeWhatsAppFactory {
    override fun create(dataDir: String, onEvent: (String) -> Unit): NativeWhatsAppClient {
        val callback = StableRef.create(onEvent)
        val handle = memScoped {
            val error = alloc<CPointerVar<ByteVar>>()
            val handle = hyd_new_whatsapp(dataDir.cstr, eventCallback, callback.asCPointer(), error.ptr)
            error.value?.let {
                val message = it.toKString()
                hyd_free(it)
                callback.dispose()
                throw IllegalStateException(message)
            }
            handle
        }
        val call = HandleCaller(handle)
        return object : NativeWhatsAppClient {
            override fun isLinked() = call("isLinked") == "true"
            override fun connect(phoneNumber: String) {
                call("connect", "phone" to phoneNumber)
            }
            override fun disconnect() {
                call("disconnect")
            }
            override fun logout() {
                call("logout")
                hyd_release(handle)
                callback.dispose()
            }
            override fun sendText(chatJid: String, text: String) = call("sendText", "threadKey" to chatJid, "text" to text)
            override fun sendReaction(chatJid: String, messageId: String, senderJid: String, emoji: String) {
                call("sendReaction", "threadKey" to chatJid, "messageId" to messageId, "senderId" to senderJid, "emoji" to emoji)
            }
            override fun markRead(chatJid: String, messageId: String, senderJid: String) {
                call("markRead", "threadKey" to chatJid, "messageId" to messageId, "senderId" to senderJid)
            }
            override fun setTyping(chatJid: String, isTyping: Boolean) {
                call("setTyping", "threadKey" to chatJid, "isTyping" to isTyping)
            }
        }
    }
}

/** Calls a hydbridge method by name with JSON arguments; throws the Go error. */
private class HandleCaller(private val handle: Long) {
    operator fun invoke(method: String, vararg args: Pair<String, Any>): String = memScoped {
        val json = JsonObject(args.associate { (key, value) ->
            key to when (value) {
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        }).toString()
        val error = alloc<CPointerVar<ByteVar>>()
        val result = hyd_call(handle, method.cstr, json.cstr, error.ptr)
        error.value?.let {
            val message = it.toKString()
            hyd_free(it)
            throw IllegalStateException(message)
        }
        result?.let { pointer -> pointer.toKString().also { hyd_free(pointer) } }.orEmpty()
    }
}

/** hydbridge's Instagram client through the static C library; only connecting and text are wired so far. */
class IosInstagramFactory : NativeMessengerFactory {
    override fun create(cookiesJson: String, dataDir: String, onEvent: (String) -> Unit): NativeMessengerClient {
        val callback = StableRef.create(onEvent)
        val handle = memScoped {
            val error = alloc<CPointerVar<ByteVar>>()
            val handle = hyd_new_instagram(cookiesJson.cstr, eventCallback, callback.asCPointer(), error.ptr)
            error.value?.let {
                val message = it.toKString()
                hyd_free(it)
                callback.dispose()
                throw IllegalStateException(message)
            }
            handle
        }
        val call = HandleCaller(handle)
        fun unsupported(): Nothing = throw UnsupportedOperationException("Not supported for Instagram yet")
        return object : NativeMessengerClient {
            override fun connect() {
                call("connect")
            }
            override fun disconnect() {
                call("disconnect")
                hyd_release(handle)
                callback.dispose()
            }
            override fun sendText(threadKey: String, text: String) = call("sendText", "threadKey" to threadKey, "text" to text)
            override fun sendEncryptedText(threadKey: String, isGroup: Boolean, text: String): String = unsupported()
            override fun requestOlder(threadKey: String, beforeTimestampMs: Long, beforeMessageId: String) = unsupported()
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
}

