package com.devvalor.howyoudoing.core.data.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.devvalor.howyoudoing.core.domain.ondevice.AccountLabel
import com.devvalor.howyoudoing.core.domain.ondevice.AccountLabels
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Each account's label as "<colour>|<name>" under "account_label:<network>/<slot>". */
class DataStoreAccountLabels(private val dataStore: DataStore<Preferences>) : AccountLabels {

    override fun observe(): Flow<Map<AccountRef, AccountLabel>> = dataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            val account = key.name.removePrefix(PREFIX).takeIf { key.name.startsWith(PREFIX) }
                ?.let(AccountRef::fromKey) ?: return@mapNotNull null
            parse(value as? String)?.let { account to it }
        }.toMap()
    }

    override suspend fun set(account: AccountRef, label: AccountLabel) {
        dataStore.edit { it[keyOf(account)] = "${label.colour}|${label.name}" }
    }

    override suspend fun remove(account: AccountRef) {
        dataStore.edit { it.remove(keyOf(account)) }
    }

    private fun keyOf(account: AccountRef) = stringPreferencesKey(PREFIX + account.key)

    private fun parse(value: String?): AccountLabel? {
        val colour = value?.substringBefore("|", missingDelimiterValue = "")?.toIntOrNull() ?: return null
        return AccountLabel(name = value.substringAfter("|"), colour = colour)
    }

    private companion object {
        const val PREFIX = "account_label:"
    }
}
