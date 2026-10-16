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

    private val KNOWN = mapOf(
        ChatNetwork.WHATSAPP to listOf(
            LoginFlowLabel("phone", "Pairing code", "Best when WhatsApp is on this phone", isRecommended = true),
            LoginFlowLabel("qr", "QR code", "Scan it with WhatsApp from another screen"),
        ),
        ChatNetwork.MESSENGER to listOf(
            LoginFlowLabel("messenger", "Messenger", "Log in on messenger.com", isRecommended = true),
            LoginFlowLabel("facebook", "Facebook account", "Log in on facebook.com"),
            LoginFlowLabel("messenger-lite", "Email or phone & password", "Sign in without a web page"),
            LoginFlowLabel("messenger-lite-android", "Email or phone & password (alternative)", "Use this if the first one is rejected"),
        ),
    )
}
