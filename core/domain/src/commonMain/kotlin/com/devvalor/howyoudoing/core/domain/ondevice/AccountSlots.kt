package com.devvalor.howyoudoing.core.domain.ondevice

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** How many accounts of one network the phone can connect at once. */
const val MAX_ACCOUNTS_PER_NETWORK = 3

/**
 * One of a network's accounts on the phone. Slot 0 is the first account, the one the app always had: it keeps its
 * folders and chat IDs, so adding the others changed nothing for it. Slots 1 and 2 are the second and third.
 */
data class AccountRef(val network: ChatNetwork, val slot: Int = 0) {
    init {
        require(slot in 0 until MAX_ACCOUNTS_PER_NETWORK) { "Slot $slot" }
    }

    /** "whatsapp/1": for keys in settings. */
    val key: String get() = "${network.name}/$slot"

    companion object {
        fun fromKey(key: String): AccountRef? {
            val (name, slot) = key.split("/").takeIf { it.size == 2 } ?: return null
            val network = ChatNetwork.entries.firstOrNull { it.name == name } ?: return null
            val number = slot.toIntOrNull()?.takeIf { it in 0 until MAX_ACCOUNTS_PER_NETWORK } ?: return null
            return AccountRef(network, number)
        }
    }
}

/**
 * What the user calls an account ("Work") and its colour, to tell a network's accounts apart in the inbox. Only
 * asked for once a network has a second account.
 */
data class AccountLabel(val name: String, val colour: Int)

/** The accounts' names and colours, kept on the phone. */
interface AccountLabels {
    fun observe(): Flow<Map<AccountRef, AccountLabel>>
    suspend fun set(account: AccountRef, label: AccountLabel)
    suspend fun remove(account: AccountRef)

    companion object {
        /** How many colours there are to choose from (the palette is the presentation's). */
        const val COLOURS = 6
    }
}

/**
 * The accounts that need telling apart, because their network has more than one logged in on the phone, with the
 * user's name and colour for each (null until they give one: screens show "Account 2" in its slot's colour).
 */
class AccountTags(private val monitor: ConnectionMonitor, private val labels: AccountLabels) {
    fun observe(): Flow<Map<AccountRef, AccountLabel?>> =
        combine(monitor.linkedAccounts, labels.observe()) { linked, named ->
            linked.groupBy { it.network }.values.filter { it.size > 1 }.flatten().associateWith { named[it] }
        }
}

/** [account] turned out to be the same account as [sameAs], already connected here, so it was disconnected. */
data class DuplicateAccount(val account: AccountRef, val sameAs: AccountRef)

/** Watches for an account logged in twice (in two slots of one network) and disconnects the newer login. */
interface DuplicateAccounts {
    /** Each login that was disconnected for being a duplicate, as it happens. */
    val rejected: Flow<DuplicateAccount>
}
