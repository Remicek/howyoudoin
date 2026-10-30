package com.devvalor.howyoudoing.core.domain.ondevice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Telegram API keys (from my.telegram.org) the user brings instead of the ones built into the app. They're used from
 * the app's next start; with none, the app's own keys are used. Keeps Telegram working for the user even if the app's
 * keys are ever blocked.
 */
interface TelegramApiKeys {
    /** False where the app can't use other keys (iOS, where Telegram isn't built yet). */
    val isSupported: Boolean

    fun observeOwn(): Flow<OwnTelegramKeys?>

    /** Saves the user's keys, or goes back to the app's own with null. */
    suspend fun setOwn(keys: OwnTelegramKeys?)

    companion object {
        private val HASH = Regex("[0-9a-f]{32}")

        /** Keys as typed: a positive number and a 32-character hexadecimal hash; null if they aren't. */
        fun parse(apiId: String, apiHash: String): OwnTelegramKeys? {
            val id = apiId.trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
            val hash = apiHash.trim().lowercase().takeIf { HASH.matches(it) } ?: return null
            return OwnTelegramKeys(id, hash)
        }
    }
}

data class OwnTelegramKeys(val apiId: Long, val apiHash: String)

/** Where Telegram isn't available. */
object NoTelegramApiKeys : TelegramApiKeys {
    override val isSupported = false
    override fun observeOwn(): Flow<OwnTelegramKeys?> = flowOf(null)
    override suspend fun setOwn(keys: OwnTelegramKeys?) = Unit
}
