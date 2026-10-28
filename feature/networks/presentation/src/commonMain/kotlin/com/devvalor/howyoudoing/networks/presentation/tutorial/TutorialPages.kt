package com.devvalor.howyoudoing.networks.presentation.tutorial

import howyoudoin.feature.networks.presentation.generated.resources.tutorial_step_1
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_step_2
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_option_1
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_option_2
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_pairing_code
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_pairing_code_sub
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_qr
import howyoudoin.feature.networks.presentation.generated.resources.tutorial_qr_sub
import howyoudoin.feature.networks.presentation.generated.resources.Res
import org.jetbrains.compose.resources.StringResource
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_connect_your_whatsapp
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_enter_the_8_character_code
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_how_do_you_want_to
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_howyoudoin_joins_your_whatsapp_as
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_howyoudoin_shows_you_a_code
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_if_howyoudoin_is_open_on
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_in_whatsapp_open_settings_on
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_open_linked_devices
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_or_scan_a_qr_code
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_tap_link_a_device
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_use_a_pairing_code
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_whatsapp_may_ask_for_your
import howyoudoin.feature.networks.presentation.generated.resources.tutorialpages_whatsapp_opens_the_camera_but
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

enum class TutorialIllustration { LINK_INTRO, SETTINGS, LINKED_DEVICES, SCANNER, CODE_ENTRY, QR_OTHER_SCREEN }

sealed interface TutorialPage {
    data class Info(
        val badge: StringResource?,
        val title: StringResource,
        val body: StringResource,
        val illustration: TutorialIllustration
    ) : TutorialPage

    /** Last page: pick a login flow (ids match the bridge's login flows). */
    data class Choice(val title: StringResource, val options: List<TutorialOption>) : TutorialPage
}

data class TutorialOption(
    val flowId: String,
    val title: StringResource,
    val description: StringResource,
    val isRecommended: Boolean
)

object TutorialPages {

    fun forNetwork(network: ChatNetwork): List<TutorialPage> = when (network) {
        ChatNetwork.WHATSAPP -> whatsApp
        else -> emptyList()
    }

    private val whatsApp = listOf(
        TutorialPage.Info(
            badge = null,
            title = Res.string.tutorialpages_connect_your_whatsapp,
            body = Res.string.tutorialpages_howyoudoin_joins_your_whatsapp_as,
            illustration = TutorialIllustration.LINK_INTRO
        ),
        TutorialPage.Info(
            badge = Res.string.tutorial_step_1,
            title = Res.string.tutorialpages_open_linked_devices,
            body = Res.string.tutorialpages_in_whatsapp_open_settings_on,
            illustration = TutorialIllustration.SETTINGS
        ),
        TutorialPage.Info(
            badge = Res.string.tutorial_step_2,
            title = Res.string.tutorialpages_tap_link_a_device,
            body = Res.string.tutorialpages_whatsapp_may_ask_for_your,
            illustration = TutorialIllustration.LINKED_DEVICES
        ),
        TutorialPage.Info(
            badge = Res.string.tutorial_option_1,
            title = Res.string.tutorialpages_use_a_pairing_code,
            body = Res.string.tutorialpages_whatsapp_opens_the_camera_but,
            illustration = TutorialIllustration.SCANNER
        ),
        TutorialPage.Info(
            badge = Res.string.tutorial_option_1,
            title = Res.string.tutorialpages_enter_the_8_character_code,
            body = Res.string.tutorialpages_howyoudoin_shows_you_a_code,
            illustration = TutorialIllustration.CODE_ENTRY
        ),
        TutorialPage.Info(
            badge = Res.string.tutorial_option_2,
            title = Res.string.tutorialpages_or_scan_a_qr_code,
            body = Res.string.tutorialpages_if_howyoudoin_is_open_on,
            illustration = TutorialIllustration.QR_OTHER_SCREEN
        ),
        TutorialPage.Choice(
            title = Res.string.tutorialpages_how_do_you_want_to,
            options = listOf(
                TutorialOption(
                    "phone",
                    Res.string.tutorial_pairing_code,
                    Res.string.tutorial_pairing_code_sub,
                    isRecommended = true
                ),
                TutorialOption("qr", Res.string.tutorial_qr, Res.string.tutorial_qr_sub, isRecommended = false),
            )
        ),
    )
}
