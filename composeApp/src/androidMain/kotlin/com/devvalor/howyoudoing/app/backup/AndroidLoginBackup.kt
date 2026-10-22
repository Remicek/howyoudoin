package com.devvalor.howyoudoing.app.backup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceLinkedAccount
import com.devvalor.howyoudoing.app.ondevice.OnDeviceSecretStore
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.auth.SessionStorage
import com.devvalor.howyoudoing.core.domain.backup.LoginBackup
import com.devvalor.howyoudoing.core.domain.backup.LoginRestoreOffer
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * Keeps the restorable logins in one file, files/login-backup/logins.json, which is all the backup rules
 * (res/xml/backup_rules.xml, data_extraction_rules.xml) let Android back up, and only end-to-end encrypted. The
 * file is rewritten while backup is on (the default) and deleted when it's off. After a restore it isn't touched until the user
 * decided what to do with it (a marker in no-backup storage, so it doesn't come back with the restore).
 */
@OptIn(FlowPreview::class)
class AndroidLoginBackup(
    context: Context,
    private val appSession: SessionStorage,
    private val matrixSession: SessionStorage,
    /** Networks restored from saved cookies, with where their cookies are kept. */
    private val cookieLogins: Map<ChatNetwork, Pair<OnDeviceMessenger, OnDeviceSecretStore>>,
    private val telegram: NativeOnDeviceLinkedAccount,
    private val telegramDir: File,
    /** Networks that can't be restored but are worth mentioning (WhatsApp, Signal). */
    private val relinkOnly: List<OnDeviceMessenger>,
    private val matrix: OnDeviceMessenger,
    private val scope: CoroutineScope
) : LoginBackup {

    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("login_backup", Context.MODE_PRIVATE)
    private val file = File(this.context.filesDir, "login-backup/logins.json")
    private val decidedMarker = File(this.context.noBackupFilesDir, "login-backup-decided")
    // On unless the user turned it off
    private val enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    private val decided = MutableStateFlow(decidedMarker.exists())
    private val offered = MutableStateFlow(prefs.getBoolean(KEY_OFFERED, false))
    private val allClients = (cookieLogins.values.map { it.first } + telegram + relinkOnly + matrix)
    private val json = Json { ignoreUnknownKeys = true }

    override val isSupported = true

    init {
        scope.launch(Dispatchers.IO) {
            // Nothing restored to offer: start keeping the backup up to date right away
            if (!decided.value && restoreOffer() == null) markDecided()
        }
        val states = allClients.map { it.state }
        combine(listOf(enabled, decided, appSession.observeAuthInfo(), matrixSession.observeAuthInfo()) + states) { it.toList() }
            .filter { decided.value }
            .debounce(WRITE_DELAY_MILLIS)
            .onEach { withContext(Dispatchers.IO) { if (enabled.value) write() else file.delete() } }
            .launchIn(scope)
    }

    override fun observeEnabled() = enabled

    // --- offering backup ---

    override fun observeShouldOfferBackup(): Flow<Boolean> =
        combine(listOf<Flow<Boolean>>(offered, decided) + allClients.map { client ->
            client.state.map { it is OnDeviceMessengerState.Connected }
        }) { values ->
            val (wasOffered, isDecided) = values[0] to values[1]
            // Not while a restore is still being offered: that would be two questions at once
            !wasOffered && isDecided && values.drop(2).count { it } >= OFFER_AFTER_CONNECTED
        }.distinctUntilChanged()

    override suspend fun answerBackupOffer(enable: Boolean) {
        prefs.edit().putBoolean(KEY_OFFERED, true).apply()
        offered.value = true
        setEnabled(enable)
    }

    override suspend fun lastBackupMillis(): Long? = withContext(Dispatchers.IO) { LoginBackupAgent.lastBackupMillis(context) }

    override fun openSystemBackupSettings() {
        // Google's own backup screen where it exists, else the system's backup / privacy settings
        val candidates = listOf(
            Intent().setClassName("com.google.android.gms", "com.google.android.gms.backup.component.BackupSettingsActivity"),
            Intent("android.settings.BACKUP_AND_RESET_SETTINGS"),
            Intent(Settings.ACTION_PRIVACY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            val started = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (started) return
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        this.enabled.value = enabled
    }

    // --- writing ---

    private suspend fun write() {
        val saved = buildJsonObject {
            put("version", 1)
            put("savedAt", System.currentTimeMillis())
            put("enabled", true)
            appSession.get()?.takeUnless { it.isDemo }?.let { put("appSession", it.toJson()) }
            matrixSession.get()?.let { put(ChatNetwork.MATRIX.name, it.toJson()) }
            cookieLogins.forEach { (network, login) ->
                val (client, secrets) = login
                if (client.state.value != OnDeviceMessengerState.NotLinked) secrets.read()?.let { put(network.name, it) }
            }
            if (telegram.state.value != OnDeviceMessengerState.NotLinked) {
                val session = File(telegramDir, TELEGRAM_SESSION).takeIf { it.isFile }?.readText()
                val state = File(telegramDir, TELEGRAM_STATE).takeIf { it.isFile }?.readText()
                if (session != null && state != null) {
                    put(ChatNetwork.TELEGRAM.name, buildJsonObject {
                        put("session", session)
                        put("state", state)
                    })
                }
            }
            putJsonArray("relink") {
                relinkOnly.filter { it.state.value != OnDeviceMessengerState.NotLinked }.forEach { add(JsonPrimitive(it.network.name)) }
            }
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "logins.json.tmp")
        tmp.writeText(saved.toString())
        tmp.renameTo(file)
    }

    // --- restoring ---

    private fun readSaved(): JsonObject? =
        file.takeIf { it.isFile }?.let { runCatching { json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }

    override suspend fun restoreOffer(): LoginRestoreOffer? = withContext(Dispatchers.IO) {
        if (decided.value) return@withContext null
        val saved = readSaved() ?: return@withContext null
        val restorable = buildList {
            cookieLogins.forEach { (network, login) -> if (network.name in saved && login.second.read() == null) add(network) }
            if (ChatNetwork.TELEGRAM.name in saved && !File(telegramDir, TELEGRAM_SESSION).isFile) add(ChatNetwork.TELEGRAM)
            if (ChatNetwork.MATRIX.name in saved && matrixSession.get() == null) add(ChatNetwork.MATRIX)
        }.sortedBy { it.ordinal }
        val includesApp = "appSession" in saved && appSession.get() == null
        val toRelink = saved["relink"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()
            .mapNotNull { name -> runCatching { ChatNetwork.valueOf(name.jsonPrimitive.content) }.getOrNull() }
            .filter { network -> relinkOnly.any { it.network == network && it.state.value == OnDeviceMessengerState.NotLinked } }
        if (restorable.isEmpty() && !includesApp && toRelink.isEmpty()) return@withContext null
        LoginRestoreOffer(
            savedAtMillis = saved["savedAt"]?.jsonPrimitive?.longOrNull ?: file.lastModified(),
            restorable = restorable,
            includesAppSession = includesApp,
            toRelink = toRelink
        )
    }

    override suspend fun restore(networks: Set<ChatNetwork>): List<ChatNetwork> {
        val saved = withContext(Dispatchers.IO) { readSaved() } ?: return networks.toList()
        val failed = mutableListOf<ChatNetwork>()
        // The app's own session first, so the inbox opens once this is done
        saved["appSession"]?.toAuthInfo()?.let { if (appSession.get() == null) appSession.set(it) }
        networks.forEach { network ->
            val ok = when (network) {
                ChatNetwork.MATRIX -> saved[network.name]?.toAuthInfo()?.let { matrixSession.set(it); true } ?: false
                ChatNetwork.TELEGRAM -> restoreTelegram(saved[network.name] as? JsonObject)
                else -> {
                    val (client, _) = cookieLogins[network] ?: return@forEach failed.plusAssign(network)
                    val cookies = saved[network.name]?.jsonPrimitive?.contentOrNull
                        ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                        ?.mapValues { it.value.jsonPrimitive.content }
                    cookies != null && client.link(cookies) is Result.Success
                }
            }
            if (!ok) failed += network
        }
        // Whoever had backup on keeps it on here
        if (saved["enabled"]?.jsonPrimitive?.booleanOrNull == true) setEnabled(true)
        markDecided()
        return failed
    }

    private suspend fun restoreTelegram(saved: JsonObject?): Boolean {
        val session = saved?.get("session")?.jsonPrimitive?.contentOrNull ?: return false
        val state = saved["state"]?.jsonPrimitive?.contentOrNull ?: return false
        withContext(Dispatchers.IO) {
            telegramDir.mkdirs()
            File(telegramDir, TELEGRAM_SESSION).writeText(session)
            File(telegramDir, TELEGRAM_STATE).writeText(state)
        }
        // Connects with the restored login; if Telegram no longer accepts it, the connection monitor says so
        telegram.reloadFromDisk()
        return true
    }

    override suspend fun discard() {
        withContext(Dispatchers.IO) { file.delete() }
        markDecided()
    }

    private suspend fun markDecided() = withContext(Dispatchers.IO) {
        decidedMarker.parentFile?.mkdirs()
        decidedMarker.createNewFile()
        decided.value = true
    }

    private fun AuthInfo.toJson() = buildJsonObject {
        put("homeserverUrl", homeserverUrl)
        put("userId", userId)
        put("accessToken", accessToken)
        put("deviceId", deviceId)
        put("isLocal", isLocal)
    }

    private fun kotlinx.serialization.json.JsonElement.toAuthInfo(): AuthInfo? = runCatching {
        val o = jsonObject
        AuthInfo(
            homeserverUrl = o.getValue("homeserverUrl").jsonPrimitive.content,
            userId = o.getValue("userId").jsonPrimitive.content,
            accessToken = o.getValue("accessToken").jsonPrimitive.content,
            deviceId = o.getValue("deviceId").jsonPrimitive.content,
            isLocal = o["isLocal"]?.jsonPrimitive?.booleanOrNull == true
        )
    }.getOrNull()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_OFFERED = "offered"
        const val OFFER_AFTER_CONNECTED = 2
        const val WRITE_DELAY_MILLIS = 5_000L
        const val TELEGRAM_SESSION = "telegram-session.json"
        const val TELEGRAM_STATE = "telegram-state.json"
    }
}
