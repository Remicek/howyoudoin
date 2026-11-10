package com.devvalor.howyoudoing.app.backup

import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceNetworks
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.backup.LoginRestoreOffer
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.AccountLabel
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.core.domain.ondevice.MAX_ACCOUNTS_PER_NETWORK
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

/** Telegram's login: its two session files, as they are on disk. */
data class TelegramFiles(val session: String, val state: String)

/** What the backed-up logins file holds (see the platform's LoginBackup). */
data class SavedLogins(
    val savedAtMillis: Long,
    /** Backup was on when this was saved, so it stays on after restoring. */
    val enabled: Boolean,
    val appSession: AuthInfo?,
    /** Accounts logged in with web cookies (Messenger, Instagram, Slack…): the cookies as a JSON object. */
    val cookieLogins: Map<AccountRef, String>,
    val matrixAccount: AuthInfo?,
    /** Telegram's accounts, by slot. */
    val telegram: Map<Int, TelegramFiles>,
    /** Connected, but never backed up: they have to be linked again (WhatsApp, Signal). */
    val relink: List<ChatNetwork>,
    /** The names and colours the user gave accounts. */
    val labels: Map<AccountRef, AccountLabel> = emptyMap()
) {
    /** The cookies of a cookie login, as name → value. */
    fun cookies(account: AccountRef): Map<String, String>? =
        cookieLogins[account]?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.mapValues { it.value.jsonPrimitive.content }

    fun cookies(network: ChatNetwork): Map<String, String>? = cookies(AccountRef(network))
}

/** What's set up on this phone right now, which decides what a restore can add. */
data class LoginsHere(
    val cookieLogins: Set<AccountRef>,
    /** Telegram's account slots logged in here. */
    val telegram: Set<Int>,
    val hasMatrixAccount: Boolean,
    val hasAppSession: Boolean,
    /** WhatsApp / Signal currently linked. */
    val linked: Set<ChatNetwork>
)

/** What a restore would offer: saved logins this phone doesn't have yet; null when there's nothing. */
fun SavedLogins.offerFor(here: LoginsHere): LoginRestoreOffer? {
    val restorable = buildList {
        cookieLogins.keys.filterNot { it in here.cookieLogins }.forEach { add(it.network) }
        if (telegram.keys.any { it !in here.telegram }) add(ChatNetwork.TELEGRAM)
        if (matrixAccount != null && !here.hasMatrixAccount) add(ChatNetwork.MATRIX)
    }.distinct().sortedBy { it.ordinal }
    val includesApp = appSession != null && !here.hasAppSession
    val toRelink = relink.filterNot { it in here.linked }
    if (restorable.isEmpty() && !includesApp && toRelink.isEmpty()) return null
    return LoginRestoreOffer(savedAtMillis, restorable, includesApp, toRelink)
}

/** The app offers backing up once two networks are connected, and only once. */
fun shouldOfferBackup(alreadyOffered: Boolean, restoreDecided: Boolean, connectedNetworks: Int): Boolean =
    !alreadyOffered && restoreDecided && connectedNetworks >= 2

/**
 * The logins file: a JSON object, version 1. A network's first account is under the network's name, as before there
 * could be more; its second and third under "WHATSAPP/1" and "WHATSAPP/2" ([AccountRef.key]).
 */
object SavedLoginsFormat {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(saved: SavedLogins): String = buildJsonObject {
        put("version", 1)
        put("savedAt", saved.savedAtMillis)
        put("enabled", saved.enabled)
        saved.appSession?.takeUnless { it.isDemo }?.let { put("appSession", it.toJson()) }
        saved.matrixAccount?.let { put(ChatNetwork.MATRIX.name, it.toJson()) }
        saved.cookieLogins.forEach { (account, cookies) -> put(account.fileKey, cookies) }
        saved.telegram.forEach { (slot, files) ->
            put(AccountRef(ChatNetwork.TELEGRAM, slot).fileKey, buildJsonObject {
                put("session", files.session)
                put("state", files.state)
            })
        }
        putJsonArray("relink") { saved.relink.forEach { add(JsonPrimitive(it.name)) } }
        if (saved.labels.isNotEmpty()) {
            put("labels", buildJsonObject {
                saved.labels.forEach { (account, label) ->
                    put(account.key, buildJsonObject {
                        put("name", label.name)
                        put("colour", label.colour)
                    })
                }
            })
        }
    }.toString()

    fun decode(text: String): SavedLogins? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        SavedLogins(
            savedAtMillis = o["savedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull == true,
            appSession = o["appSession"]?.toAuthInfo(),
            cookieLogins = COOKIE_NETWORKS.flatMap { network -> slots(network) }.mapNotNull { account ->
                o[account.fileKey]?.jsonPrimitive?.contentOrNull?.let { account to it }
            }.toMap(),
            matrixAccount = o[ChatNetwork.MATRIX.name]?.toAuthInfo(),
            telegram = slots(ChatNetwork.TELEGRAM).mapNotNull { account ->
                val t = o[account.fileKey] as? JsonObject ?: return@mapNotNull null
                val session = t["session"]?.jsonPrimitive?.contentOrNull
                val state = t["state"]?.jsonPrimitive?.contentOrNull
                if (session != null && state != null) account.slot to TelegramFiles(session, state) else null
            }.toMap(),
            relink = o["relink"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()
                .mapNotNull { name -> ChatNetwork.entries.firstOrNull { it.name == name.jsonPrimitive.contentOrNull } },
            labels = (o["labels"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                val account = AccountRef.fromKey(key) ?: return@mapNotNull null
                val label = value as? JsonObject ?: return@mapNotNull null
                val name = label["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val colour = label["colour"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                account to AccountLabel(name, colour)
            }.toMap()
        )
    }.getOrNull()

    /** Networks restored from their saved login (web cookies, or Bluesky's handle and app password). */
    val COOKIE_NETWORKS = listOf(ChatNetwork.MESSENGER, ChatNetwork.INSTAGRAM, ChatNetwork.SLACK) +
        OnDeviceNetworks.WEB_LOGIN

    /** Where an account's login is in the file: the first under the network's name, as it always was. */
    private val AccountRef.fileKey: String get() = if (slot == 0) network.name else key

    private fun slots(network: ChatNetwork) = (0 until MAX_ACCOUNTS_PER_NETWORK).map { AccountRef(network, it) }

    private fun AuthInfo.toJson() = buildJsonObject {
        put("homeserverUrl", homeserverUrl)
        put("userId", userId)
        put("accessToken", accessToken)
        put("deviceId", deviceId)
        put("isLocal", isLocal)
    }

    private fun JsonElement.toAuthInfo(): AuthInfo? = runCatching {
        val o = jsonObject
        AuthInfo(
            homeserverUrl = o.getValue("homeserverUrl").jsonPrimitive.content,
            userId = o.getValue("userId").jsonPrimitive.content,
            accessToken = o.getValue("accessToken").jsonPrimitive.content,
            deviceId = o.getValue("deviceId").jsonPrimitive.content,
            isLocal = o["isLocal"]?.jsonPrimitive?.booleanOrNull == true
        )
    }.getOrNull()
}
