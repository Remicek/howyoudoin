package com.relay.networks.domain

/** A way to log in to a network offered by its bridge, e.g. WhatsApp "QR code" or "Pairing code". */
data class BridgeLoginFlow(
    val id: String,
    val name: String,
    val description: String
)
