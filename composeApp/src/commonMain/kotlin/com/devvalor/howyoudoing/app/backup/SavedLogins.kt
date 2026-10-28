package com.devvalor.howyoudoing.app.backup

import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceNetworks
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.backup.LoginRestoreOffer
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
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
    /** Networks logged in with web cookies (Messenger, Instagram, Slack): the cookies as a JSON object. */
    val cookieLogins: Map<ChatNetwork, String>,
    val matrixAccount: AuthInfo?,
    val telegram: TelegramFiles?,
    /** Connected, but never backed up: they have to be linked again (WhatsApp, Signal). */
    val relink: List<ChatNetwork>
) {
    /** The cookies of a cookie login, as name → value. */
    fun cookies(network: ChatNetwork): Map<String, String>? =
        cookieLogins[network]?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.mapValues { it.value.jsonPrimitive.content }
}

/** What's set up on this phone right now, which decides what a restore can add. */
data class LoginsHere(
    val cookieLogins: Set<ChatNetwork>,
    val hasTelegram: Boolean,
    val hasMatrixAccount: Boolean,
    val hasAppSession: Boolean,
    /** WhatsApp / Signal currently linked. */
    val linked: Set<ChatNetwork>
)

/** What a restore would offer: saved logins this phone doesn't have yet; null when there's nothing. */
fun SavedLogins.offerFor(here: LoginsHere): LoginRestoreOffer? {
    val restorable = buildList {
        cookieLogins.keys.filterNot { it in here.cookieLogins }.forEach(::add)
        if (telegram != null && !here.hasTelegram) add(ChatNetwork.TELEGRAM)
        if (matrixAccount != null && !here.hasMatrixAccount) add(ChatNetwork.MATRIX)
    }.sortedBy { it.ordinal }
    val includesApp = appSession != null && !here.hasAppSession
    val toRelink = relink.filterNot { it in here.linked }
    if (restorable.isEmpty() && !includesApp && toRelink.isEmpty()) return null
    return LoginRestoreOffer(savedAtMillis, restorable, includesApp, toRelink)
}

/** The app offers backing up once two networks are connected, and only once. */
fun shouldOfferBackup(alreadyOffered: Boolean, restoreDecided: Boolean, connectedNetworks: Int): Boolean =
    !alreadyOffered && restoreDecided && connectedNetworks >= 2

/** The logins file: a JSON object, version 1. */
object SavedLoginsFormat {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(saved: SavedLogins): String = buildJsonObject {
        put("version", 1)
        put("savedAt", saved.savedAtMillis)
        put("enabled", saved.enabled)
        saved.appSession?.takeUnless { it.isDemo }?.let { put("appSession", it.toJson()) }
        saved.matrixAccount?.let { put(ChatNetwork.MATRIX.name, it.toJson()) }
        saved.cookieLogins.forEach { (network, cookies) -> put(network.name, cookies) }
        saved.telegram?.let {
            put(ChatNetwork.TELEGRAM.name, buildJsonObject {
                put("session", it.session)
                put("state", it.state)
            })
        }
        putJsonArray("relink") { saved.relink.forEach { add(JsonPrimitive(it.name)) } }
    }.toString()

    fun decode(text: String): SavedLogins? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        SavedLogins(
            savedAtMillis = o["savedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull == true,
            appSession = o["appSession"]?.toAuthInfo(),
            cookieLogins = COOKIE_NETWORKS.mapNotNull { network ->
                o[network.name]?.jsonPrimitive?.contentOrNull?.let { network to it }
            }.toMap(),
            matrixAccount = o[ChatNetwork.MATRIX.name]?.toAuthInfo(),
            telegram = (o[ChatNetwork.TELEGRAM.name] as? JsonObject)?.let { t ->
                val session = t["session"]?.jsonPrimitive?.contentOrNull
                val state = t["state"]?.jsonPrimitive?.contentOrNull
                if (session != null && state != null) TelegramFiles(session, state) else null
            },
            relink = o["relink"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()
                .mapNotNull { name -> ChatNetwork.entries.firstOrNull { it.name == name.jsonPrimitive.contentOrNull } }
        )
    }.getOrNull()

    /** Networks restored from their saved login (web cookies, or Bluesky's handle and app password). */
    val COOKIE_NETWORKS = listOf(ChatNetwork.MESSENGER, ChatNetwork.INSTAGRAM, ChatNetwork.SLACK) +
        OnDeviceNetworks.WEB_LOGIN

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
