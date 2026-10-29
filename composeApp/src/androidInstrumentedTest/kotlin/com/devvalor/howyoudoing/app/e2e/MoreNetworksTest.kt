package com.devvalor.howyoudoing.app.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * The networks that connect from the phone without a bridge and were added later: Google Messages, Bluesky,
 * LinkedIn, Discord and Google Voice. Some steps reach the real services (Bluesky's sign-in, Google's pairing QR
 * code), so the emulator needs the internet.
 */
@RunWith(AndroidJUnit4::class)
class MoreNetworksTest {

    private val rules = AppTestRules()
    private val app = rules.app

    @get:Rule
    val chain: TestRule = rules.chain

    @Test
    fun newNetworksAreListedAsUntested() {
        openNetworks()
        UNTESTED_NETWORKS.forEach { network ->
            scrollTo(network)
            app.waitForNode(hasText(UNTESTED, substring = true) and hasParent(hasAnyDescendant(hasText(network))))
        }
    }

    @Test
    fun demoInboxHasAChatFromEachNewNetwork() {
        startDemo()
        listOf("Game Night 🎮", "City Pharmacy", "Jamie Lee", "Priya Shah", "Sam Rivera", "Mr. Novak").forEach { chat ->
            app.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(chat))
            app.waitForText(chat)
        }
    }

    /** Bluesky's real server turns down a made-up app password, and the app says so. */
    @Test
    fun blueskySignInReportsAWrongAppPassword() {
        openNetworks()
        connect("Bluesky")
        acceptRiskIfAsked()
        app.waitForText("Sign in with your handle and an app password", substring = true)
        app.onNodeWithText("On this device").performClick()
        app.waitForText("Use an app password", substring = true)
        val fields = app.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("howyoudoin-test-no-such-user.bsky.social")
        fields[1].performTextInput("aaaa-bbbb-cccc-dddd")
        app.onNodeWithText("Continue").performClick()
        app.waitForText("Wrong Bluesky handle or app password", timeoutMillis = NETWORK_TIMEOUT_MILLIS)
    }

    @Test
    fun discordWarnsAboutBansEveryTime() {
        openNetworks()
        repeat(2) {
            connect("Discord")
            app.waitForText(DISCORD_WARNING)
            app.onNodeWithText("Cancel").performClick()
            app.waitForText("Discord")
        }
        connect("Discord")
        app.waitForText(DISCORD_WARNING)
        app.onNodeWithText("I understand, continue").performClick()
        app.waitForText("Discord may ban accounts that do this", substring = true)
    }

    /** Google's pairing service hands out a real QR code, shown with how to scan it in Google Messages. */
    @Test
    fun googleMessagesShowsAPairingQrCode() {
        openNetworks()
        connect("Google Messages")
        acceptRiskIfAsked()
        app.waitForText("On this device · QR code")
        app.onNodeWithText("On this device · QR code").performClick()
        app.waitForText("Device pairing", substring = true, timeoutMillis = NETWORK_TIMEOUT_MILLIS)
    }

    @Test
    fun webLoginsOpenTheirSignInPage() {
        openNetworks()
        listOf(
            "LinkedIn" to "Enter your LinkedIn email",
            "X" to "Enter your X email",
            "Google Voice" to "Enter your Google email",
        ).forEach { (network, instructions) ->
            connect(network)
            acceptRiskIfAsked()
            app.waitForText("On this device")
            app.onNodeWithText("On this device").performClick()
            app.waitForText(instructions, substring = true)
            app.pressBack()
            app.waitForText(network)
        }
    }

    private fun startDemo() {
        app.waitForText("Try demo mode")
        app.onNodeWithText("Try demo mode").performClick()
        app.waitForText("Lock HowYouDoin?")
        app.onNodeWithText("Not now").performClick()
        app.clickIfShown("Skip")
        app.waitForText("Mom")
    }

    private fun openNetworks() {
        startDemo()
        app.onNodeWithText("Settings").performClick()
        app.waitForText("App lock")
        app.scrollToAndClick("Networks")
        app.waitForText("Connect your chat accounts", substring = true)
    }

    private fun scrollTo(network: String) {
        app.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(network))
    }

    private fun connect(network: String) {
        scrollTo(network)
        val button: SemanticsMatcher =
            (hasText("Connect") or hasText("Add")) and hasParent(hasAnyDescendant(hasText(network)))
        app.waitUntil(NETWORK_TIMEOUT_MILLIS) { app.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        app.onNode(button).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun acceptRiskIfAsked() {
        app.waitForIdle()
        if (app.onAllNodes(hasText("I understand, continue")).fetchSemanticsNodes().isNotEmpty()) {
            app.onNodeWithText("I understand, continue").performClick()
        }
    }

    private companion object {
        /** Networks not yet connected with a real account (Bluesky has been). */
        val UNTESTED_NETWORKS = listOf("Google Messages", "LinkedIn", "Discord", "X", "Google Voice")
        const val UNTESTED = "Untested"
        const val DISCORD_WARNING = "Discord bans accounts for this"
        const val NETWORK_TIMEOUT_MILLIS = 30_000L
    }
}
