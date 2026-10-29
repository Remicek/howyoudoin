package com.devvalor.howyoudoing.app.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * End-to-end walk through the app in demo mode (no accounts or servers needed): the welcome screen, a chat, every
 * screen Settings and About lead to, the themes, and the warning before connecting a network.
 *
 * Run on an emulator: ./gradlew :composeApp:connectedDebugAndroidTest (it clears the app's data, so not on a phone
 * you use).
 */
@RunWith(AndroidJUnit4::class)
class AppJourneyTest {

    private val rules = AppTestRules()
    private val app = rules.app

    @get:Rule
    val chain: TestRule = rules.chain

    @Test
    fun welcomeScreenExplainsThatTheAppHasNoServers() {
        app.waitForText("All your chats. One place.")
        app.onNodeWithText("Your chats stay on your phone").assertIsDisplayed()
        app.onNodeWithText("No HowYouDoin servers").assertIsDisplayed()
        app.onNodeWithText("We have no servers and no accounts", substring = true).assertIsDisplayed()
        app.onNodeWithText("Try demo mode").assertIsDisplayed()
    }

    @Test
    fun demoChatSendsAMessage() {
        startDemo()
        app.onNodeWithText("Mom").performClick()
        app.waitForText("Dad is making pierogi")

        app.onNode(hasSetTextAction()).performTextInput("Hello from the end-to-end test")
        app.onNodeWithContentDescription("Send").performClick()
        app.waitForText("Hello from the end-to-end test")

        // Still there after leaving and reopening the chat (the demo contact answers in the meantime)
        app.pressBack()
        app.waitForText("Mom")
        app.onNodeWithText("Mom").performClick()
        app.waitForText("Hello from the end-to-end test")
    }

    @Test
    fun everySettingsAndAboutLinkOpensItsScreen() {
        startDemo()
        openSettings()

        // Each row, and something only its screen shows
        val destinations = listOf(
            "Networks" to "Connect your chat accounts",
            "How connecting works" to "Messenger and Instagram",
            "Appearance" to "Electric blue and violet, the HowYouDoin look",
            "App lock" to "Turn on app lock",
            "Crash reports" to "Offer to share after a crash",
            "Open-source licenses" to "HowYouDoin itself is free software",
            "Terms of use" to "Risk to your accounts",
            "About HowYouDoin" to "Free software under the GNU AGPL v3",
        )
        for ((row, content) in destinations) {
            app.scrollToAndClick(row)
            app.waitForText(content, substring = true)
            app.pressBack()
            app.waitForText("Settings")
        }

        // About links further to the terms and licenses
        app.scrollToAndClick("About HowYouDoin")
        app.waitForText("Free software under the GNU AGPL v3", substring = true)
        app.scrollToAndClick("Terms of use")
        app.waitForText("Risk to your accounts")
        app.pressBack()
        app.scrollToAndClick("Open-source licenses")
        app.waitForText("HowYouDoin itself is free software", substring = true)
        app.pressBack()
        app.waitForText("Good to know")
    }

    @Test
    fun chosenThemeAndDarkModeAreKeptAfterRestart() {
        startDemo()
        openSettings()
        app.scrollToAndClick("Appearance")
        app.waitForText("Graphite")
        app.onNodeWithText("On").performClick()
        app.scrollToAndClick("Graphite")
        app.waitForNode(hasContentDescription("Selected"))

        app.activityRule.scenario.recreate()
        app.waitForText("Graphite")
        app.onNode(hasText("On") and isSelected()).assertExists()
        // Graphite is the last option; its tick is the one shown
        app.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Graphite"))
        app.waitForNode(hasContentDescription("Selected"))
        app.onNodeWithText("Black and white", substring = true).assertIsDisplayed()
    }

    @Test
    fun connectingWarnsOnceAboutBlockedAccounts() {
        startDemo()
        openSettings()
        app.scrollToAndClick("Networks")
        app.waitForText("Connect your chat accounts", substring = true)

        connectWhatsApp()
        app.waitForText("Your account could be blocked")

        // The terms open from the warning, which is still there afterwards
        app.onNodeWithText("Read the terms of use").performClick()
        app.waitForText("Risk to your accounts")
        app.pressBack()
        app.waitForText("Your account could be blocked")

        app.onNodeWithText("I understand, continue").performClick()
        app.waitForText("Connect your WhatsApp")
        app.pressBack()

        // Accepted once: connecting again goes straight on
        app.waitForText("Connect your chat accounts", substring = true)
        connectWhatsApp()
        app.waitForText("Connect your WhatsApp")
        assert(app.onAllNodesWithText("Your account could be blocked").fetchSemanticsNodes().isEmpty())
    }

    private fun startDemo() {
        app.waitForText("Try demo mode")
        app.onNodeWithText("Try demo mode").performClick()
        // Asked once after signing in
        app.waitForText("Lock HowYouDoin?")
        app.onNodeWithText("Not now").performClick()
        app.clickIfShown("Skip")
        app.waitForText("Mom")
    }

    private fun openSettings() {
        app.onNodeWithText("Settings").performClick()
        app.waitForText("App lock")
    }

    /** WhatsApp's button: "Add" in demo mode, where an account is already connected, otherwise "Connect". */
    private fun connectWhatsApp() {
        val button = (hasText("Connect") or hasText("Add")) and hasParent(hasAnyDescendant(hasText("WhatsApp")))
        // The cards show their buttons once each network's status has loaded
        app.waitUntil(15_000) { app.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        app.onNode(button).performSemanticsAction(SemanticsActions.OnClick)
    }
}
