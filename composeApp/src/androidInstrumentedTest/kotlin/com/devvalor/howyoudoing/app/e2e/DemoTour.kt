package com.devvalor.howyoudoing.app.e2e

import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * A slow walk through demo mode for the README's GIF: the welcome screen, the inbox, a chat, and how WhatsApp
 * (a QR code) and Messenger (its login page) are connected from the phone. It records the screen to
 * /sdcard/tour.mp4 and only runs when asked for (the Demo GIF workflow passes
 * -Pandroid.testInstrumentationRunnerArguments.demoTour=true); normal test runs skip it.
 */
@RunWith(AndroidJUnit4::class)
class DemoTour {

    private val rules = AppTestRules()
    private val app = rules.app

    @get:Rule
    val chain: TestRule = rules.chain

    @Before
    fun startRecording() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("demoTour") == "true")
        shell("rm -f $VIDEO")
        // Runs until stopped in stopRecording
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screenrecord --bit-rate 6000000 $VIDEO")
        pause(SHORT)
    }

    @After
    fun stopRecording() {
        if (InstrumentationRegistry.getArguments().getString("demoTour") != "true") return
        pause(SHORT)
        shell("pkill -INT screenrecord")
        // screenrecord finishes writing the file after it's stopped
        Thread.sleep(FINISH_WRITING_MILLIS)
    }

    @Test
    fun tour() {
        welcomeAndInbox()
        chat()
        connectWhatsApp()
        connectMessenger()
        callsInfo()
        // Back to the inbox
        app.pressBack()
        app.pressBack()
        app.waitForText("Mom")
        pause(LONG)
    }

    /** The welcome screen (what the app is, and that it has no servers), then every network in one inbox. */
    private fun welcomeAndInbox() {
        app.waitForText("All your chats. One place.")
        pause(LONG)
        app.onNodeWithText("Try demo mode").assertIsDisplayed()
        pause(LONG)
        app.onNodeWithText("Try demo mode").performClick()
        app.waitForText("Lock HowYouDoin?")
        pause(SHORT)
        app.onNodeWithText("Not now").performClick()
        app.clickIfShown("Skip")

        app.waitForText("Mom")
        pause(LONG)
    }

    /** A chat: send a message, the demo contact answers. */
    private fun chat() {
        app.onNodeWithText("Mom").performClick()
        app.waitForText("Dad is making pierogi")
        pause(SHORT)
        // Set without focusing the field, so no keyboard covers the chat in the recording
        app.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString("Yes! I'll bring dessert 🍰"))
        }
        pause(SHORT)
        app.onNodeWithContentDescription("Send").performClick()
        pause(REPLY)
        app.pressBack()
        app.waitForText("Mom")
        pause(SHORT)
    }

    /** Networks: WhatsApp links like WhatsApp Web, with a QR code shown on this phone. */
    private fun connectWhatsApp() {
        pause(SHORT)
        app.onNodeWithText("Settings").performClick()
        app.waitForText("App lock")
        app.scrollToAndClick("Networks")
        app.waitForText("Connect your chat accounts", substring = true)
        pause(LONG)
        connect("WhatsApp")
        app.waitForText("Your account could be blocked")
        pause(LONG)
        app.onNodeWithText("I understand, continue").performClick()
        // A short tutorial shows how linking works
        app.waitForText("How to connect")
        pause(LONG)
        app.onNodeWithText("Next").performClick()
        pause(LONG)
        app.onNodeWithText("Skip").performClick()
        app.waitForText("QR code")
        pause(SHORT)
        app.onNodeWithText("QR code").performClick()
        // A real pairing code from WhatsApp, to scan with the phone that has the account
        app.waitForText("Waiting for confirmation", substring = true, timeoutMillis = LINKING_TIMEOUT_MILLIS)
        pause(QR)
        app.pressBack()
    }

    /** Messenger: its own login page, inside the app. */
    private fun connectMessenger() {
        app.waitForText("Connect your chat accounts", substring = true)
        pause(SHORT)
        connect("Messenger")
        app.waitForText("Log in on messenger.com", substring = true)
        pause(SHORT)
        app.onAllNodesWithText("Messenger")[0].performClick()
        pause(WEB_PAGE)
        app.pressBack()
    }

    /** Matrix's wiggling microphone and camera: calls go through Matrix for now. */
    private fun callsInfo() {
        app.waitForText("Connect your chat accounts", substring = true)
        val badge = hasContentDescription("Voice and video calls", substring = true)
        app.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(badge)
        pause(CALLS_BADGE)
        app.onNode(badge).performClick()
        pause(LONG)
        pause(LONG)
    }

    /** The network card's button: "Add" when an account is already connected, otherwise "Connect". */
    private fun connect(network: String) {
        val button: SemanticsMatcher =
            (hasText("Connect") or hasText("Add")) and hasParent(hasAnyDescendant(hasText(network)))
        app.waitUntil(LINKING_TIMEOUT_MILLIS) { app.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        app.onNode(button).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun pause(millis: Long) {
        app.waitForIdle()
        Thread.sleep(millis)
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }

    private companion object {
        const val VIDEO = "/sdcard/tour.mp4"
        const val SHORT = 1_200L
        const val LONG = 2_500L

        /** Long enough for the demo contact's answer. */
        const val REPLY = 4_000L
        const val QR = 5_000L
        const val WEB_PAGE = 7_000L

        /** Long enough to see the icons wiggle a couple of times. */
        const val CALLS_BADGE = 6_000L
        const val LINKING_TIMEOUT_MILLIS = 30_000L
        const val FINISH_WRITING_MILLIS = 3_000L
    }
}
