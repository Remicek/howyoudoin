package com.devvalor.howyoudoing.networks.presentation.tutorial

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork

enum class TutorialIllustration { LINK_INTRO, SETTINGS, LINKED_DEVICES, SCANNER, CODE_ENTRY, QR_OTHER_SCREEN }

sealed interface TutorialPage {
    data class Info(
        val badge: String?,
        val title: String,
        val body: String,
        val illustration: TutorialIllustration
    ) : TutorialPage

    /** Last page: pick a login flow (ids match the bridge's login flows). */
    data class Choice(val title: String, val options: List<TutorialOption>) : TutorialPage
}

data class TutorialOption(
    val flowId: String,
    val title: String,
    val description: String,
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
            title = "Connect your WhatsApp",
            body = "HowYouDoin joins your WhatsApp as a linked device, just like WhatsApp Web. " +
                "Your phone stays the main device and you can unlink it at any time.",
            illustration = TutorialIllustration.LINK_INTRO
        ),
        TutorialPage.Info(
            badge = "Step 1",
            title = "Open Linked devices",
            body = "In WhatsApp, open Settings (on Android: ⋮ menu → Settings) and tap Linked devices.",
            illustration = TutorialIllustration.SETTINGS
        ),
        TutorialPage.Info(
            badge = "Step 2",
            title = "Tap “Link a device”",
            body = "WhatsApp may ask for your fingerprint, face or PIN to confirm it's you.",
            illustration = TutorialIllustration.LINKED_DEVICES
        ),
        TutorialPage.Info(
            badge = "Option 1 · On this phone",
            title = "Use a pairing code",
            body = "WhatsApp opens the camera, but you can't scan your own screen. " +
                "Tap “Link with phone number instead” at the bottom.",
            illustration = TutorialIllustration.SCANNER
        ),
        TutorialPage.Info(
            badge = "Option 1 · On this phone",
            title = "Enter the 8-character code",
            body = "HowYouDoin shows you a code like 1CDB-N2DG. Copy it, switch to WhatsApp and type or paste it.",
            illustration = TutorialIllustration.CODE_ENTRY
        ),
        TutorialPage.Info(
            badge = "Option 2 · Another screen",
            title = "Or scan a QR code",
            body = "If HowYouDoin is open on a tablet or computer, point WhatsApp's camera at the QR code it shows.",
            illustration = TutorialIllustration.QR_OTHER_SCREEN
        ),
        TutorialPage.Choice(
            title = "How do you want to connect?",
            options = listOf(
                TutorialOption("phone", "Pairing code", "Best when WhatsApp is on this phone", isRecommended = true),
                TutorialOption("qr", "QR code", "Scan it from another screen", isRecommended = false),
            )
        ),
    )
}
