package com.devvalor.howyoudoing.core.domain.network

/**
 * Chat networks HowYouDoin can bridge into the unified inbox.
 *
 * [bridgeProtocolIds] are the `protocol.id` values mautrix bridges publish in the
 * `m.bridge` room state event, and [ghostUserPrefixes] the localpart prefixes of the
 * puppet users they create. Both are used to tell which network a Matrix room belongs to.
 *
 * Only networks with [isSupported] can be connected for now; the rest are TODO.
 */
enum class ChatNetwork(
    val displayName: String,
    val isSupported: Boolean,
    val bridgeProtocolIds: Set<String> = emptySet(),
    val ghostUserPrefixes: Set<String> = emptySet()
) {
    WHATSAPP(
        displayName = "WhatsApp",
        isSupported = true,
        bridgeProtocolIds = setOf("whatsapp"),
        ghostUserPrefixes = setOf("whatsapp_", "whatsappbot")
    ),
    MESSENGER(
        displayName = "Messenger",
        isSupported = true,
        bridgeProtocolIds = setOf("facebook", "messenger", "meta"),
        ghostUserPrefixes = setOf("meta_", "messenger_", "facebook_", "metabot", "messengerbot")
    ),

    // TODO: bridges below are not wired up yet
    TELEGRAM(displayName = "Telegram", isSupported = false, bridgeProtocolIds = setOf("telegram"), ghostUserPrefixes = setOf("telegram_")),
    SIGNAL(displayName = "Signal", isSupported = false, bridgeProtocolIds = setOf("signal"), ghostUserPrefixes = setOf("signal_")),
    INSTAGRAM(displayName = "Instagram", isSupported = false, bridgeProtocolIds = setOf("instagram"), ghostUserPrefixes = setOf("instagram_")),
    DISCORD(displayName = "Discord", isSupported = false, bridgeProtocolIds = setOf("discord"), ghostUserPrefixes = setOf("discord_")),
    SLACK(displayName = "Slack", isSupported = false, bridgeProtocolIds = setOf("slack"), ghostUserPrefixes = setOf("slack_")),
    LINKEDIN(displayName = "LinkedIn", isSupported = false, bridgeProtocolIds = setOf("linkedin"), ghostUserPrefixes = setOf("linkedin_")),
    GOOGLE_MESSAGES(displayName = "Google Messages", isSupported = false, bridgeProtocolIds = setOf("gmessages"), ghostUserPrefixes = setOf("gmessages_")),

    /** Native Matrix rooms, no bridge involved. */
    MATRIX(displayName = "Matrix", isSupported = false);

    val isBridged: Boolean get() = this != MATRIX

    companion object {
        val bridged: List<ChatNetwork> = entries.filter { it.isBridged }

        fun fromProtocolId(protocolId: String): ChatNetwork? =
            entries.firstOrNull { protocolId.lowercase() in it.bridgeProtocolIds }

        fun fromUserId(userId: String): ChatNetwork? {
            val localpart = userId.removePrefix("@").substringBefore(":").lowercase()
            return entries.firstOrNull { network ->
                network.ghostUserPrefixes.any { localpart.startsWith(it) }
            }
        }
    }
}
