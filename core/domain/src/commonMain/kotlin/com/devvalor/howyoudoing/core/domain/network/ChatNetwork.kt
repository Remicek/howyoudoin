package com.devvalor.howyoudoing.core.domain.network

/**
 * Chat networks HowYouDoin can bridge into the unified inbox.
 *
 * [bridgeProtocolIds] are the `protocol.id` values mautrix bridges publish in the
 * `m.bridge` room state event, and [ghostUserPrefixes] the localpart prefixes of the
 * puppet users they create. Both are used to tell which network a Matrix room belongs to.
 *
 * [isSupported] networks have a server bridge set up; the rest connect only from the phone. [isUntested] ones
 * haven't been connected with a real account yet, so the app says connecting them may not work. Networks that aren't
 * [isAvailable] keep their client but aren't offered on the networks screen. [calls] says what the app can do with
 * the network's calls.
 */
// Each parameter is one plain fact about the network, with defaults; grouping them would only add a layer
@Suppress("LongParameterList")
enum class ChatNetwork(
    val displayName: String,
    val isSupported: Boolean,
    val bridgeProtocolIds: Set<String> = emptySet(),
    val ghostUserPrefixes: Set<String> = emptySet(),
    val isUntested: Boolean = false,
    val isAvailable: Boolean = true,
    val calls: CallSupport = CallSupport.NOT_SHOWN
) {
    WHATSAPP(
        displayName = "WhatsApp",
        isSupported = true,
        bridgeProtocolIds = setOf("whatsapp"),
        ghostUserPrefixes = setOf("whatsapp_", "whatsappbot"),
        calls = CallSupport.SHOWN
    ),
    MESSENGER(
        displayName = "Messenger",
        isSupported = true,
        bridgeProtocolIds = setOf("facebook", "messenger", "meta"),
        ghostUserPrefixes = setOf("meta_", "messenger_", "facebook_", "metabot", "messengerbot"),
        // Chats are end-to-end encrypted by default, and calls in those never reach HowYouDoin (tested 2026-11-04)
        calls = CallSupport.NOT_SHOWN
    ),

    // No server bridge set up for these (isSupported); they connect on the device
    TELEGRAM(
        displayName = "Telegram",
        isSupported = false,
        bridgeProtocolIds = setOf("telegram"),
        ghostUserPrefixes = setOf("telegram_"),
        calls = CallSupport.SHOWN
    ),
    SIGNAL(
        displayName = "Signal",
        isSupported = false,
        bridgeProtocolIds = setOf("signal"),
        ghostUserPrefixes = setOf("signal_"),
        calls = CallSupport.SHOWN
    ),
    INSTAGRAM(
        displayName = "Instagram",
        isSupported = false,
        bridgeProtocolIds = setOf("instagram"),
        ghostUserPrefixes = setOf("instagram_"),
        calls = CallSupport.JOINED
    ),
    SLACK(
        displayName = "Slack",
        isSupported = false,
        bridgeProtocolIds = setOf("slack"),
        ghostUserPrefixes = setOf("slack_"),
        calls = CallSupport.SHOWN
    ),

    /**
     * SMS and RCS through the Google Messages app on the user's Android phone, paired like Messages for web. Not
     * offered: the Messages app dropped QR pairing in 2026, and Google refuses its account sign-in in the app's
     * login browser ("This browser or app may not be secure").
     */
    GOOGLE_MESSAGES(
        displayName = "Google Messages",
        isSupported = false,
        bridgeProtocolIds = setOf("gmessages"),
        ghostUserPrefixes = setOf("gmessages_"),
        isUntested = true,
        isAvailable = false
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
        ghostUserPrefixes = setOf("linkedin_")
    ),

    /** Direct messages and group DMs only; server channels stay out of the inbox. */
    DISCORD(
        displayName = "Discord",
        isSupported = false,
        bridgeProtocolIds = setOf("discord"),
        ghostUserPrefixes = setOf("discord_"),
        calls = CallSupport.SHOWN
    ),
    /** X's direct messages (X Chat), shown as "X". */
    TWITTER(
        displayName = "X",
        isSupported = false,
        bridgeProtocolIds = setOf("twitter"),
        ghostUserPrefixes = setOf("twitter_"),
        // X Chat only posts a call's entry once it's over (a ringing call isn't announced to other clients)
        calls = CallSupport.LOGGED
    ),
    /** Not offered: Google refuses its sign-in in the app's login browser, as for [GOOGLE_MESSAGES]. */
    GOOGLE_VOICE(
        displayName = "Google Voice",
        isSupported = false,
        bridgeProtocolIds = setOf("gvoice"),
        ghostUserPrefixes = setOf("gvoice_"),
        isUntested = true,
        isAvailable = false,
        calls = CallSupport.LOGGED
    ),

    /** Native Matrix rooms, no bridge involved. */
    MATRIX(displayName = "Matrix", isSupported = false, calls = CallSupport.IN_APP);

    val isBridged: Boolean get() = this != MATRIX

    companion object {
        val bridged: List<ChatNetwork> = entries.filter { it.isBridged }

        /** What the networks screen lists: the bridged networks and a Matrix account of its own. */
        val connectable: List<ChatNetwork> = bridged.filter { it.isAvailable } + MATRIX

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

/** What HowYouDoin does with a network's audio and video calls. */
enum class CallSupport {
    /** Calls are made and answered in HowYouDoin (Matrix). */
    IN_APP,

    /**
     * Calls ring in the network's own app only, but HowYouDoin notifies about them as they ring ("Incoming video
     * call · answer it in WhatsApp") and keeps missed and ended calls in the chat.
     */
    SHOWN,

    /**
     * A call is a room started in the network's app that others join there: HowYouDoin notifies when one starts ("A
     * call started · join it in Instagram") and keeps it in the chat, but can't tell whether the user joined.
     */
    JOINED,

    /** Only the network's call log reaches HowYouDoin: missed and ended calls show in the chat, without ringing. */
    LOGGED,

    /** The network doesn't tell other apps about calls (or has none), so they aren't shown at all. */
    NOT_SHOWN
}
