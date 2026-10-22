package com.devvalor.howyoudoing.networks.presentation.connect

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.networks.domain.BridgeLoginFlow

/** How a bridge login flow is presented; the bridge's own name/description are often technical. */
data class LoginFlowLabel(
    val flowId: String,
    val title: String,
    val description: String,
    val isRecommended: Boolean = false
)

object LoginFlowLabels {

    /**
     * Known flows are relabelled and put in a sensible order (recommended first); flows we don't
     * know yet keep the bridge's text and go last, so new bridge versions still work.
     */
    fun present(network: ChatNetwork, flows: List<BridgeLoginFlow>): List<LoginFlowLabel> {
        val known = KNOWN[network].orEmpty()
        return flows
            .sortedBy { flow -> known.indexOfFirst { it.flowId == flow.id }.takeIf { it >= 0 } ?: Int.MAX_VALUE }
            .map { flow ->
                known.firstOrNull { it.flowId == flow.id }
                    ?: LoginFlowLabel(flow.id, flow.name, flow.description)
            }
    }

    // Connecting on this phone is the default; the server bridge flows remain as an alternative
    private val KNOWN = mapOf(
        ChatNetwork.WHATSAPP to listOf(
            LoginFlowLabel(ON_DEVICE_WHATSAPP_CODE, "Pairing code", "Best when WhatsApp is on this phone", isRecommended = true),
            LoginFlowLabel(ON_DEVICE_WHATSAPP_QR, "QR code", "Scan it with WhatsApp on another phone"),
            LoginFlowLabel("phone", "Pairing code via server bridge", "Connects through your Matrix server instead"),
            LoginFlowLabel("qr", "QR code via server bridge", "Connects through your Matrix server instead"),
        ),
        ChatNetwork.MESSENGER to listOf(
            LoginFlowLabel(ON_DEVICE, "Messenger", "Log in on messenger.com; chats connect from this phone", isRecommended = true),
            LoginFlowLabel("messenger", "Messenger via server bridge", "Log in on messenger.com; chats connect through your server"),
            LoginFlowLabel("facebook", "Facebook account via server bridge", "Log in on facebook.com"),
            LoginFlowLabel("messenger-lite", "Email or phone & password via server bridge", "Sign in without a web page"),
            LoginFlowLabel("messenger-lite-android", "Email or phone & password (alternative)", "Use this if the first one is rejected"),
        ),
        ChatNetwork.TELEGRAM to listOf(
            LoginFlowLabel(ON_DEVICE_TELEGRAM, "Phone number", "Log in with a code Telegram sends you; chats connect from this phone", isRecommended = true),
        ),
        ChatNetwork.SIGNAL to listOf(
            LoginFlowLabel(ON_DEVICE_SIGNAL, "QR code", "Link this app in Signal on your phone, like Signal Desktop", isRecommended = true),
        ),
        ChatNetwork.MATRIX to listOf(
            LoginFlowLabel("howyoudoin-matrix-account", "Matrix account", "Sign in with your Matrix ID and password", isRecommended = true),
        ),
        ChatNetwork.SLACK to listOf(
            LoginFlowLabel(ON_DEVICE, "Slack", "Sign in on slack.com; your workspace connects from this phone", isRecommended = true),
        ),
        ChatNetwork.GOOGLE_MESSAGES to listOf(
            LoginFlowLabel(ON_DEVICE_GOOGLE_QR, "QR code", "Scan it with Google Messages, from another screen (e.g. a computer)", isRecommended = true),
            LoginFlowLabel(
                ON_DEVICE_GOOGLE_ACCOUNT, "Google account",
                "Sign in with Google, then tap an emoji on your phone. Google often refuses sign-in inside apps"
            ),
        ),
        ChatNetwork.INSTAGRAM to listOf(
            LoginFlowLabel(ON_DEVICE, "Instagram", "Log in on instagram.com; direct messages connect from this phone", isRecommended = true),
        ),
    )

    // Flow IDs of the on-device options (see ConnectNetworkViewModel)
    private const val ON_DEVICE = "howyoudoin-on-device"
    private const val ON_DEVICE_WHATSAPP_QR = "howyoudoin-on-device-whatsapp-qr"
    private const val ON_DEVICE_WHATSAPP_CODE = "howyoudoin-on-device-whatsapp-code"
    private const val ON_DEVICE_TELEGRAM = "howyoudoin-on-device-telegram"
    private const val ON_DEVICE_SIGNAL = "howyoudoin-on-device-signal"
    private const val ON_DEVICE_GOOGLE_ACCOUNT = "howyoudoin-on-device-google-account"
    private const val ON_DEVICE_GOOGLE_QR = "howyoudoin-on-device-google-qr"
}
