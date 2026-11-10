package com.devvalor.howyoudoing.app.backup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.devvalor.howyoudoing.app.ondevice.NativeOnDeviceLinkedAccount
import com.devvalor.howyoudoing.app.ondevice.OnDeviceSecretStore
import com.devvalor.howyoudoing.core.domain.auth.SessionStorage
import com.devvalor.howyoudoing.core.domain.backup.LoginBackup
import com.devvalor.howyoudoing.core.domain.backup.LoginRestoreOffer
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.AccountLabel
import com.devvalor.howyoudoing.core.domain.ondevice.AccountLabels
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.app.ondevice.IN_USE_MARKER
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
import java.io.File

/**
 * Keeps the restorable logins in one file, files/login-backup/logins.json, which is all the backup rules
 * (res/xml/backup_rules.xml, data_extraction_rules.xml) let Android back up: to the cloud only in an end-to-end
 * encrypted backup (tied to the user's screen lock), and in a local device-to-device transfer to a new phone. The
 * file is rewritten while backup is on (the default) and deleted when it's off. After a restore it isn't touched until
 * the user decided what to do with it (a marker in no-backup storage, so it doesn't come back with the restore). The
 * file format and what to offer are in SavedLogins.kt.
 */
@OptIn(FlowPreview::class)
class AndroidLoginBackup(
    context: Context,
    private val appSession: SessionStorage,
    private val matrixSession: SessionStorage,
    sources: BackupSources,
    private val scope: CoroutineScope
) : LoginBackup {

    /** The accounts whose logins are backed up, and where they keep them. */
    data class BackupSources(
        /** Accounts restored from saved cookies, with where their cookies are kept. */
        val cookieLogins: Map<AccountRef, Pair<OnDeviceMessenger, OnDeviceSecretStore>>,
        /** Telegram's accounts by slot, with the folder of each one's session files. */
        val telegram: Map<Int, Pair<NativeOnDeviceLinkedAccount, File>>,
        /** Accounts that can't be restored but are worth mentioning (WhatsApp, Signal). */
        val relinkOnly: List<OnDeviceMessenger>,
        val matrix: OnDeviceMessenger,
        /** The accounts' names and colours, kept with the logins. */
        val labels: AccountLabels
    )

    private val cookieLogins = sources.cookieLogins
    private val telegram = sources.telegram
    private val relinkOnly = sources.relinkOnly
    private val matrix = sources.matrix
    private val labels = sources.labels

    /** The latest names and colours, for writing. */
    @Volatile
    private var currentLabels: Map<AccountRef, AccountLabel> = emptyMap()

    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("login_backup", Context.MODE_PRIVATE)
    private val file = File(this.context.filesDir, "login-backup/logins.json")
    private val decidedMarker = File(this.context.noBackupFilesDir, "login-backup-decided")

    // On unless the user turned it off
    private val enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    private val decided = MutableStateFlow(decidedMarker.exists())
    private val offered = MutableStateFlow(prefs.getBoolean(KEY_OFFERED, false))
    private val allClients =
        cookieLogins.values.map { it.first } + telegram.values.map { it.first } + relinkOnly + matrix

    override val isSupported = true

    init {
        scope.launch(Dispatchers.IO) {
            // Nothing restored to offer: start keeping the backup up to date right away
            if (!decided.value && restoreOffer() == null) markDecided()
        }
        val states = allClients.map { it.state }
        combine(
            listOf(
                enabled,
                decided,
                appSession.observeAuthInfo(),
                matrixSession.observeAuthInfo(),
                labels.observe().onEach { currentLabels = it }
            ) + states
        ) { it.toList() }
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
            // Not while a restore is still being offered: that would be two questions at once
            shouldOfferBackup(
                alreadyOffered = values[0],
                restoreDecided = values[1],
                connectedNetworks = values.drop(2).count { it })
        }.distinctUntilChanged()

    override suspend fun answerBackupOffer(enable: Boolean) {
        prefs.edit().putBoolean(KEY_OFFERED, true).apply()
        offered.value = true
        setEnabled(enable)
    }

    override suspend fun lastBackupMillis(): Long? =
        withContext(Dispatchers.IO) { LoginBackupAgent.lastBackupMillis(context) }

    override fun openSystemBackupSettings() {
        // Google's own backup screen where it exists, else the system's backup / privacy settings
        val candidates = listOf(
            Intent().setClassName(
                "com.google.android.gms",
                "com.google.android.gms.backup.component.BackupSettingsActivity"
            ),
            Intent("android.settings.BACKUP_AND_RESET_SETTINGS"),
            Intent(Settings.ACTION_PRIVACY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            val started =
                runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (started) return
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        this.enabled.value = enabled
    }

    // --- writing ---

    private suspend fun write() {
        fun File.textOrNull() = takeIf { it.isFile }?.readText()
        val saved = SavedLogins(
            savedAtMillis = System.currentTimeMillis(),
            enabled = true,
            appSession = appSession.get(),
            cookieLogins = cookieLogins.mapNotNull { (account, login) ->
                val (client, secrets) = login
                secrets.read()?.takeIf { client.state.value != OnDeviceMessengerState.NotLinked }?.let { account to it }
            }.toMap(),
            matrixAccount = matrixSession.get(),
            telegram = telegram.mapNotNull { (slot, login) ->
                val (client, dir) = login
                if (client.state.value == OnDeviceMessengerState.NotLinked) return@mapNotNull null
                val session = File(dir, TELEGRAM_SESSION).textOrNull()
                val state = File(dir, TELEGRAM_STATE).textOrNull()
                if (session != null && state != null) slot to TelegramFiles(session, state) else null
            }.toMap(),
            relink = relinkOnly.filter { it.state.value != OnDeviceMessengerState.NotLinked }.map { it.network }
                .distinct(),
            labels = currentLabels
        )
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "logins.json.tmp")
        tmp.writeText(SavedLoginsFormat.encode(saved))
        tmp.renameTo(file)
    }

    // --- restoring ---

    private fun readSaved(): SavedLogins? = file.takeIf { it.isFile }?.let { SavedLoginsFormat.decode(it.readText()) }

    override suspend fun restoreOffer(): LoginRestoreOffer? = withContext(Dispatchers.IO) {
        if (decided.value) return@withContext null
        val saved = readSaved() ?: return@withContext null
        saved.offerFor(
            LoginsHere(
                cookieLogins = cookieLogins.filterValues { (_, secrets) -> secrets.read() != null }.keys,
                telegram = telegram.filterValues { (_, dir) -> File(dir, TELEGRAM_SESSION).isFile }.keys,
                hasMatrixAccount = matrixSession.get() != null,
                hasAppSession = appSession.get() != null,
                linked = relinkOnly.filter { it.state.value != OnDeviceMessengerState.NotLinked }.map { it.network }
                    .toSet()
            )
        )
    }

    override suspend fun restore(networks: Set<ChatNetwork>): List<ChatNetwork> {
        val saved = withContext(Dispatchers.IO) { readSaved() } ?: return networks.toList()
        val failed = mutableListOf<ChatNetwork>()
        // The app's own session first, so the inbox opens once this is done
        saved.appSession?.let { if (appSession.get() == null) appSession.set(it) }
        networks.forEach { network -> if (!restoreNetwork(saved, network)) failed += network }
        saved.labels.forEach { (account, label) ->
            if (account.network in networks && account !in currentLabels) labels.set(account, label)
        }
        // Whoever had backup on keeps it on here
        if (saved.enabled) setEnabled(true)
        markDecided()
        return failed
    }

    /** Every saved account of [network]; false if none came back. */
    private suspend fun restoreNetwork(saved: SavedLogins, network: ChatNetwork): Boolean = when (network) {
        ChatNetwork.MATRIX -> saved.matrixAccount?.let { matrixSession.set(it); true } ?: false
        ChatNetwork.TELEGRAM -> saved.telegram.map { (slot, files) -> restoreTelegram(slot, files) }.any { it }
        else -> saved.cookieLogins.keys.filter { it.network == network }.map { account ->
            val client = cookieLogins[account]?.first
            val cookies = saved.cookies(account)
            client != null && cookies != null && client.link(cookies) is Result.Success
        }.any { it }
    }

    private suspend fun restoreTelegram(slot: Int, saved: TelegramFiles): Boolean {
        val (client, dir) = telegram[slot] ?: return false
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            File(dir, TELEGRAM_SESSION).writeText(saved.session)
            File(dir, TELEGRAM_STATE).writeText(saved.state)
            // A second or third account is only opened once it's in use
            File(dir, IN_USE_MARKER).createNewFile()
        }
        // Connects with the restored login; if Telegram no longer accepts it, the connection monitor says so
        client.reloadFromDisk()
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

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_OFFERED = "offered"
        const val WRITE_DELAY_MILLIS = 5_000L
        const val TELEGRAM_SESSION = "telegram-session.json"
        const val TELEGRAM_STATE = "telegram-state.json"
    }
}
