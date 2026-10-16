package com.devvalor.howyoudoing.networks.domain

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

data class BridgeStatus(
    val network: ChatNetwork,
    val flows: List<BridgeLoginFlow>,
    val logins: List<NetworkLogin>
)

/** An account connected through a bridge, e.g. one WhatsApp phone number. */
data class NetworkLogin(
    val id: String,
    val name: String,
    val state: String?
)
