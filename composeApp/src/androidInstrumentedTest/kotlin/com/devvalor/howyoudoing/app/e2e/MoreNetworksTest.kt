package com.devvalor.howyoudoing.app.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
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
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * The networks that connect from the phone without a bridge and were added later: Bluesky, LinkedIn, Discord and X
 * (Google Messages and Google Voice aren't offered). Bluesky's sign-in reaches the real service, so the emulator
 * needs the internet.
 */
@RunWith(AndroidJUnit4::class)
class MoreNetworksTest {

    private val rules = AppTestRules()
    private val app = rules.app

    @get:Rule
    val chain: TestRule = rules.chain

    @Test
    fun newNetworksAreNoLongerMarkedUntested() {
        openNetworks()
        // All of them have been tried with real accounts now (Discord last), so none says "Untested"
        NEW_NETWORKS.forEach { network ->
            scrollTo(network)
            app.waitForText(network)
            check(app.onAllNodes(hasText(UNTESTED, substring = true)).fetchSemanticsNodes().isEmpty()) {
                "$network is marked untested"
            }
        }
    }

    @Test
    fun demoInboxHasAChatFromEachNewNetwork() {
        startDemo()
        listOf("Game Night 🎮", "Jamie Lee", "Priya Shah", "Sam Rivera").forEach { chat ->
            app.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(chat))
            app.waitForText(chat)
        }
    }

    /**
     * Bluesky's real server turns down a made-up app password, and the app says so (or that it's had too many sign-ins
     * to try, after several runs in a row).
     *
     * Quarantined from CI: it reaches Bluesky's live server, so it flakes on network timeouts and rate limits and
     * reddens otherwise-green runs. Remove @Ignore to run it locally against the real server.
     */
    @Ignore("Live Bluesky sign-in: flaky in CI (network timeouts / rate limits). Run locally when needed.")
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
        app.waitForNode(
            hasText("Wrong Bluesky handle or app password", substring = true) or
                hasText("Too many sign-ins", substring = true),
            NETWORK_TIMEOUT_MILLIS
        )
    }

    @Test
    fun discordWarnsAboutBansEveryTime() {
        openNetworks()
        repeat(2) {
            connect("Discord")
            app.waitForText(DISCORD_WARNING)
            app.onNodeWithText("Cancel").performClick()
            app.waitForText("Networks")
        }
        connect("Discord")
        app.waitForText(DISCORD_WARNING)
        app.onNodeWithText("I understand, continue").performClick()
        app.waitForText("Discord may ban accounts that do this", substring = true)
    }

    @Test
    fun googleNetworksAreNotOffered() {
        openNetworks()
        scrollTo("Matrix")
        app.onAllNodes(hasText("Google Messages")).assertCountEquals(0)
        app.onAllNodes(hasText("Google Voice")).assertCountEquals(0)
    }

    @Test
    fun webLoginsOpenTheirSignInPage() {
        openNetworks()
        listOf(
            "LinkedIn" to "Enter your LinkedIn email",
            "X" to "Enter your X email",
        ).forEach { (network, instructions) ->
            connect(network)
            acceptRiskIfAsked()
            app.waitForText("On this device")
            app.onNodeWithText("On this device").performClick()
            app.waitForText(instructions, substring = true)
            app.pressBack()
            app.waitForText("Networks")
            scrollTo(network)
        }
    }

    private fun startDemo() {
        app.waitForText("Try demo mode")
        app.onNodeWithText("Try demo mode").performClick()
        app.waitForText("Lock HowYouDoin?")
        app.onNodeWithText("Not now").performClick()
        app.clickIfShown("Skip")
        app.waitForText("Mom")
        app.skipTour()
    }

    private fun openNetworks() {
        startDemo()
        app.onNodeWithText("Settings").performClick()
        app.waitForText("App lock")
        app.scrollToAndClick("Networks")
        app.waitForText("Connect your chat accounts", substring = true)
        // The demo accounts load a moment later and rebuild the list, which can undo a scroll made before then
        app.waitForText("Disconnect")
    }

    /** Scrolls the list to [network], again if the list was rebuilt meanwhile, until its name is on screen. */
    private fun scrollTo(network: String) {
        app.waitUntil(NETWORK_TIMEOUT_MILLIS) {
            app.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(network))
            app.onAllNodes(hasText(network)).fetchSemanticsNodes().isNotEmpty()
        }
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
        /** The networks added later; all have been tried with real accounts. */
        val NEW_NETWORKS = listOf("Bluesky", "LinkedIn", "Discord")
        const val UNTESTED = "Untested"
        const val DISCORD_WARNING = "Discord bans accounts for this"
        const val NETWORK_TIMEOUT_MILLIS = 30_000L
    }
}
