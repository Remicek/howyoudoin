package com.devvalor.howyoudoing.core.domain.network

/**
 * Chat networks HowYouDoin can bridge into the unified inbox.
 *
 * [bridgeProtocolIds] are the `protocol.id` values mautrix bridges publish in the
 * `m.bridge` room state event, and [ghostUserPrefixes] the localpart prefixes of the
 * puppet users they create. Both are used to tell which network a Matrix room belongs to.
 *
 * [isSupported] networks have a server bridge set up; the rest connect only from the phone. [isUntested] ones
 * haven't been connected with a real account yet, so the app says connecting them may not work.
 */
enum class ChatNetwork(
    val displayName: String,
    val isSupported: Boolean,
    val bridgeProtocolIds: Set<String> = emptySet(),
    val ghostUserPrefixes: Set<String> = emptySet(),
    val isUntested: Boolean = false
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

    // No server bridge set up for these (isSupported); they connect on the device
    TELEGRAM(
        displayName = "Telegram",
        isSupported = false,
        bridgeProtocolIds = setOf("telegram"),
        ghostUserPrefixes = setOf("telegram_")
    ),
    SIGNAL(
        displayName = "Signal",
        isSupported = false,
        bridgeProtocolIds = setOf("signal"),
        ghostUserPrefixes = setOf("signal_")
    ),
    INSTAGRAM(
        displayName = "Instagram",
        isSupported = false,
        bridgeProtocolIds = setOf("instagram"),
        ghostUserPrefixes = setOf("instagram_")
    ),
    SLACK(
        displayName = "Slack",
        isSupported = false,
        bridgeProtocolIds = setOf("slack"),
        ghostUserPrefixes = setOf("slack_")
    ),

    /** SMS and RCS through the Google Messages app on the user's Android phone, paired like Messages for web. */
    GOOGLE_MESSAGES(
        displayName = "Google Messages",
        isSupported = false,
        bridgeProtocolIds = setOf("gmessages"),
        ghostUserPrefixes = setOf("gmessages_"),
        isUntested = true
    ),
    BLUESKY(
        displayName = "Bluesky",
        isSupported = false,
        bridgeProtocolIds = setOf("bluesky"),
        ghostUserPrefixes = setOf("bluesky_")
    ),
    LINKEDIN(
        displayName = "LinkedIn",
        isSupported = false,
        bridgeProtocolIds = setOf("linkedin"),
        ghostUserPrefixes = setOf("linkedin_"),
        isUntested = true
    ),

    /** Direct messages and group DMs only; server channels stay out of the inbox. */
    DISCORD(
        displayName = "Discord",
        isSupported = false,
        bridgeProtocolIds = setOf("discord"),
        ghostUserPrefixes = setOf("discord_"),
        isUntested = true
    ),
    /** X's direct messages (X Chat), shown as "X". */
    TWITTER(
        displayName = "X",
        isSupported = false,
        bridgeProtocolIds = setOf("twitter"),
        ghostUserPrefixes = setOf("twitter_"),
        isUntested = true
    ),
    GOOGLE_VOICE(
        displayName = "Google Voice",
        isSupported = false,
        bridgeProtocolIds = setOf("gvoice"),
        ghostUserPrefixes = setOf("gvoice_"),
        isUntested = true
    ),

    /** Native Matrix rooms, no bridge involved. */
    MATRIX(displayName = "Matrix", isSupported = false);

    val isBridged: Boolean get() = this != MATRIX

    companion object {
        val bridged: List<ChatNetwork> = entries.filter { it.isBridged }

        /** What the networks screen lists: the bridged networks and a Matrix account of its own. */
        val connectable: List<ChatNetwork> = bridged + MATRIX

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
