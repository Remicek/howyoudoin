package com.devvalor.howyoudoing.app.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** The main screens in demo mode: writing to someone new, the Contacts tab, and logging out from Settings. */
@RunWith(AndroidJUnit4::class)
class HomeTest {

    private val rules = AppTestRules()
    private val app = rules.app

    @get:Rule
    val chain: TestRule = rules.chain

    /** "+" → New message → WhatsApp → someone without a chat yet: the chat opens, and stays in the inbox. */
    @Test
    fun newMessageStartsAChatWithSomeoneNew() {
        startDemo()
        app.onNodeWithContentDescription("New message").performClick()
        app.waitForText("Where do you want to write?")
        app.onNodeWithText("WhatsApp").performClick()
        app.onNode(hasSetTextAction()).performTextInput("ewa")
        app.waitForText("Ewa Kowalska")
        app.onNodeWithText("Ewa Kowalska").performClick()

        app.waitForNode(hasText("Ewa Kowalska") and !hasSetTextAction())
        app.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString("Hi Ewa!"))
        }
        app.onNodeWithContentDescription("Send").performClick()
        app.waitForText("Hi Ewa!")

        // Back goes to the inbox (the new message screen was replaced by the chat), which now has the chat
        app.pressBack()
        app.waitForText("Ewa Kowalska")
        app.waitForText("Mom")
    }

    /** The Contacts tab lists everyone, and its filter shows one network's people. */
    @Test
    fun contactsTabFiltersByNetwork() {
        startDemo()
        app.onNodeWithText("Contacts").performClick()
        app.waitForText("Maya Chen")
        app.waitForText("Grandma")
        app.onNode(hasText("Messenger") and hasClickAncestorOrSelf()).performClick()
        app.waitForText("Maya Chen")
        app.waitUntil(TIMEOUT_MILLIS) { app.onAllNodes(hasText("Grandma")).fetchSemanticsNodes().isEmpty() }
        // Chats tab goes back to the inbox
        app.onNodeWithText("Chats").performClick()
        app.waitForText("Mom")
    }

    /** A new user is walked through the inbox once; afterwards the inbox works and Settings can show it again. */
    @Test
    fun tourWalksThroughTheInboxOnce() {
        startDemo(skipTour = false)
        listOf("Connect your networks", "Filter by network", "Start a new chat").forEach { title ->
            app.waitForText(title)
            app.onNodeWithText("Next").performClick()
        }
        app.waitForText("Find a chat")
        app.onNodeWithText("Got it").performClick()
        app.waitUntil(TIMEOUT_MILLIS) { app.onAllNodes(hasText("Find a chat")).fetchSemanticsNodes().isEmpty() }

        // The inbox can be used now, and the tour doesn't come back by itself
        app.onNodeWithText("Settings").performClick()
        app.waitForText("App lock")
        app.pressBack()
        app.waitForText("Mom")
        check(app.onAllNodes(hasText(SKIP_TOUR)).fetchSemanticsNodes().isEmpty()) { "The tour came back" }

        // Settings shows it again
        app.onNodeWithText("Settings").performClick()
        app.waitForText("App lock")
        app.scrollToAndClick("Inbox tour")
        app.waitForText("Connect your networks")
        app.skipTour()
    }

    /** Settings → About offers rating the app and sharing it. */
    @Test
    fun settingsOffersRatingAndSharing() {
        startDemo()
        app.onNodeWithText("Settings").performClick()
        app.waitForText("App lock")
        app.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Share HowYouDoin"))
        app.waitForText("Rate HowYouDoin")
        app.waitForText("Share HowYouDoin")
    }

    @Test
    fun settingsLogsOut() {
        startDemo()
        app.onNodeWithText("Settings").performClick()
        app.waitForText("App lock")
        app.scrollToAndClick("Log out")
        app.waitForText("Try demo mode")
    }

    @Test
    fun plusOffersToAddANetwork() {
        startDemo()
        app.onNodeWithContentDescription("Add network").performClick()
        app.waitForText("Connect your chat accounts", substring = true)
        app.waitForNode(hasContentDescription("Voice and video calls", substring = true) or hasText("WhatsApp"))
    }

    private fun startDemo(skipTour: Boolean = true) {
        app.waitForText("Try demo mode")
        app.onNodeWithText("Try demo mode").performClick()
        app.waitForText("Lock HowYouDoin?")
        app.onNodeWithText("Not now").performClick()
        app.clickIfShown("Skip")
        app.waitForText("Mom")
        if (skipTour) app.skipTour()
    }

    /** The filter chip, not other text that says "Messenger". */
    private fun hasClickAncestorOrSelf() =
        androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
