package com.devvalor.howyoudoing.core.domain.ondevice

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

/** Every network the app can connect straight from the phone, for screens that list or link them. */
data class OnDeviceAccounts(
    val messenger: OnDeviceMessenger,
    val whatsApp: OnDeviceWhatsApp,
    val instagram: OnDeviceInstagram,
    val telegram: OnDeviceTelegram,
    val signal: OnDeviceSignal,
    val slack: OnDeviceSlack,
    val matrix: OnDeviceMatrix,
    val more: OnDeviceNetworks = OnDeviceNetworks(),
    /** The networks' second and third accounts (slots 1 and 2), where the phone can have more than one. */
    val extra: ExtraAccounts = ExtraAccounts()
) {
    /** The first account of each network, then the others. */
    val all: List<OnDeviceMessenger>
        get() = listOf(messenger, whatsApp, instagram, telegram, signal, slack, matrix) + more.clients.values +
            extra.clients

    /** The client of [network]'s first account, if the phone can connect it. */
    fun forNetwork(network: ChatNetwork): OnDeviceMessenger? = forAccount(AccountRef(network))

    fun forAccount(account: AccountRef): OnDeviceMessenger? = all.firstOrNull { it.account == account }

    /** Every account slot of [network], first account first (linked or not). */
    fun accountsOf(network: ChatNetwork): List<OnDeviceMessenger> =
        all.filter { it.network == network }.sortedBy { it.slot }

    /** Whether [network] can have more than one account on the phone. */
    fun hasExtraAccounts(network: ChatNetwork): Boolean = extra.clients.any { it.network == network }
}

/**
 * The networks added after the first few (Google Messages, Bluesky, LinkedIn, Discord, X, Google Voice), by network:
 * they share one client class each, so they're looked up rather than each having a field of its own.
 */
data class OnDeviceNetworks(val clients: Map<ChatNetwork, OnDeviceMessenger> = emptyMap()) {
    companion object {
        /** Networks logged in from their web page or with a password, through one shared native client. */
        val WEB_LOGIN = listOf(
            ChatNetwork.BLUESKY,
            ChatNetwork.LINKEDIN,
            ChatNetwork.DISCORD,
            ChatNetwork.TWITTER,
            ChatNetwork.GOOGLE_VOICE
        )

        /** Networks paired with the user's phone (a QR code or an emoji tapped there). */
        val PAIRED = listOf(ChatNetwork.GOOGLE_MESSAGES)

        val ALL = PAIRED + WEB_LOGIN
    }
}

/** The clients of the networks' second and third accounts on the phone (see [AccountRef]). */
data class ExtraAccounts(val clients: List<OnDeviceMessenger> = emptyList())
