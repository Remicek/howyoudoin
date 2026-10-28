package com.devvalor.howyoudoing.networks.presentation.connect

import com.devvalor.howyoudoing.core.presentation.util.UiText
import com.devvalor.howyoudoing.core.presentation.util.asUiText
import howyoudoin.feature.networks.presentation.generated.resources.Res
import howyoudoin.feature.networks.presentation.generated.resources.flow_best_when_whatsapp_is_on_this
import howyoudoin.feature.networks.presentation.generated.resources.flow_connects_through_your_matrix_server_instead
import howyoudoin.feature.networks.presentation.generated.resources.flow_email_or_phone_password_alternative
import howyoudoin.feature.networks.presentation.generated.resources.flow_email_or_phone_password_via_server
import howyoudoin.feature.networks.presentation.generated.resources.flow_facebook_account_via_server_bridge
import howyoudoin.feature.networks.presentation.generated.resources.flow_link_this_app_in_signal_on
import howyoudoin.feature.networks.presentation.generated.resources.flow_log_in_on_facebook_com
import howyoudoin.feature.networks.presentation.generated.resources.flow_log_in_on_instagram_com_direct
import howyoudoin.feature.networks.presentation.generated.resources.flow_log_in_on_messenger_com_chats
import howyoudoin.feature.networks.presentation.generated.resources.flow_log_in_on_messenger_com_phone
import howyoudoin.feature.networks.presentation.generated.resources.flow_log_in_with_a_code_telegram
import howyoudoin.feature.networks.presentation.generated.resources.flow_matrix_account
import howyoudoin.feature.networks.presentation.generated.resources.flow_messenger_via_server_bridge
import howyoudoin.feature.networks.presentation.generated.resources.flow_pairing_code
import howyoudoin.feature.networks.presentation.generated.resources.flow_pairing_code_via_server_bridge
import howyoudoin.feature.networks.presentation.generated.resources.flow_phone_number
import howyoudoin.feature.networks.presentation.generated.resources.flow_qr_code
import howyoudoin.feature.networks.presentation.generated.resources.flow_qr_code_via_server_bridge
import howyoudoin.feature.networks.presentation.generated.resources.flow_scan_it_with_whatsapp_on_another
import howyoudoin.feature.networks.presentation.generated.resources.flow_sign_in_on_slack_com_your
import howyoudoin.feature.networks.presentation.generated.resources.flow_sign_in_with_your_matrix_id
import howyoudoin.feature.networks.presentation.generated.resources.flow_sign_in_without_a_web_page
import howyoudoin.feature.networks.presentation.generated.resources.flow_use_this_if_the_first_one
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.networks.domain.BridgeLoginFlow

/** How a bridge login flow is presented; the bridge's own name/description are often technical. */
data class LoginFlowLabel(
    val flowId: String,
    val title: UiText,
    val description: UiText,
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
                    ?: LoginFlowLabel(flow.id, flow.name.asUiText(), flow.description.asUiText())
            }
    }

    // Connecting on this phone is the default; the server bridge flows remain as an alternative
    private val KNOWN = mapOf(
        ChatNetwork.WHATSAPP to listOf(
            LoginFlowLabel(
                ON_DEVICE_WHATSAPP_CODE,
                UiText.Resource(Res.string.flow_pairing_code),
                UiText.Resource(Res.string.flow_best_when_whatsapp_is_on_this),
                isRecommended = true
            ),
            LoginFlowLabel(
                ON_DEVICE_WHATSAPP_QR,
                UiText.Resource(Res.string.flow_qr_code),
                UiText.Resource(Res.string.flow_scan_it_with_whatsapp_on_another)
            ),
            LoginFlowLabel(
                "phone",
                UiText.Resource(Res.string.flow_pairing_code_via_server_bridge),
                UiText.Resource(Res.string.flow_connects_through_your_matrix_server_instead)
            ),
            LoginFlowLabel(
                "qr",
                UiText.Resource(Res.string.flow_qr_code_via_server_bridge),
                UiText.Resource(Res.string.flow_connects_through_your_matrix_server_instead)
            ),
        ),
        ChatNetwork.MESSENGER to listOf(
            LoginFlowLabel(
                ON_DEVICE,
                "Messenger".asUiText(),
                UiText.Resource(Res.string.flow_log_in_on_messenger_com_phone),
                isRecommended = true
            ),
            LoginFlowLabel(
                "messenger",
                UiText.Resource(Res.string.flow_messenger_via_server_bridge),
                UiText.Resource(Res.string.flow_log_in_on_messenger_com_chats)
            ),
            LoginFlowLabel(
                "facebook",
                UiText.Resource(Res.string.flow_facebook_account_via_server_bridge),
                UiText.Resource(Res.string.flow_log_in_on_facebook_com)
            ),
            LoginFlowLabel(
                "messenger-lite",
                UiText.Resource(Res.string.flow_email_or_phone_password_via_server),
                UiText.Resource(Res.string.flow_sign_in_without_a_web_page)
            ),
            LoginFlowLabel(
                "messenger-lite-android",
                UiText.Resource(Res.string.flow_email_or_phone_password_alternative),
                UiText.Resource(Res.string.flow_use_this_if_the_first_one)
            ),
        ),
        ChatNetwork.TELEGRAM to listOf(
            LoginFlowLabel(
                ON_DEVICE_TELEGRAM,
                UiText.Resource(Res.string.flow_phone_number),
                UiText.Resource(Res.string.flow_log_in_with_a_code_telegram),
                isRecommended = true
            ),
        ),
        ChatNetwork.SIGNAL to listOf(
            LoginFlowLabel(
                ON_DEVICE_SIGNAL,
                UiText.Resource(Res.string.flow_qr_code),
                UiText.Resource(Res.string.flow_link_this_app_in_signal_on),
                isRecommended = true
            ),
        ),
        ChatNetwork.MATRIX to listOf(
            LoginFlowLabel(
                "howyoudoin-matrix-account",
                UiText.Resource(Res.string.flow_matrix_account),
                UiText.Resource(Res.string.flow_sign_in_with_your_matrix_id),
                isRecommended = true
            ),
        ),
        ChatNetwork.SLACK to listOf(
            LoginFlowLabel(
                ON_DEVICE,
                "Slack".asUiText(),
                UiText.Resource(Res.string.flow_sign_in_on_slack_com_your),
                isRecommended = true
            ),
        ),
        ChatNetwork.INSTAGRAM to listOf(
            LoginFlowLabel(
                ON_DEVICE,
                "Instagram".asUiText(),
                UiText.Resource(Res.string.flow_log_in_on_instagram_com_direct),
                isRecommended = true
            ),
        ),
    )

    // Flow IDs of the on-device options (see ConnectNetworkViewModel)
    private const val ON_DEVICE = "howyoudoin-on-device"
    private const val ON_DEVICE_WHATSAPP_QR = "howyoudoin-on-device-whatsapp-qr"
    private const val ON_DEVICE_WHATSAPP_CODE = "howyoudoin-on-device-whatsapp-code"
    private const val ON_DEVICE_TELEGRAM = "howyoudoin-on-device-telegram"
    private const val ON_DEVICE_SIGNAL = "howyoudoin-on-device-signal"
}
