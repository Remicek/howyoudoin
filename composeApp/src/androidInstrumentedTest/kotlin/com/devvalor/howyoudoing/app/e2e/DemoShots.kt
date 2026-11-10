package com.devvalor.howyoudoing.app.e2e

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Captures clean store screenshots from demo mode (invented people and messages only), one PNG per screen into
 * /sdcard/hyd_shots/. It only runs when asked for (the capture passes
 * -Pandroid.testInstrumentationRunnerArguments.demoShots=true); normal test runs skip it.
 *
 * Mirrors [DemoTour]; where that records a video, this stops at each screen and takes a screenshot.
 */
@RunWith(AndroidJUnit4::class)
class DemoShots {

    private val rules = AppTestRules()
    private val app = rules.app

    @get:Rule
    val chain: TestRule = rules.chain

    @Test
    fun capture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("demoShots") == "true")
        // executeShellCommand runs a single command (no shell operators), so issue these separately
        shell("rm -rf $DIR")
        shell("mkdir -p $DIR")

        startDemo()
        pause()
        screencap("01_inbox_light")

        // A chat: Mom, with the demo conversation
        app.onNodeWithText("Mom").performClick()
        app.waitForText("Dad is making pierogi")
        pause()
        screencap("02_chat")
        app.pressBack()
        app.waitForText("Mom")

        // Contacts: everyone, across networks
        app.onNodeWithText("Contacts").performClick()
        app.waitForText("Maya Chen")
        pause()
        screencap("03_contacts")
        app.onNodeWithText("Chats").performClick()
        app.waitForText("Mom")

        // Settings
        app.onNodeWithText("Settings").performClick()
        app.waitForText("App lock")
        pause()
        screencap("04_settings")

        // Networks: the full roster, including the newer ones (Discord, Bluesky, LinkedIn, X)
        app.scrollToAndClick("Networks")
        app.waitForText("Connect your chat accounts", substring = true)
        pause()
        screencap("05_networks")
        app.pressBack()
        app.waitForText("App lock")

        // Appearance: themes (light)
        app.scrollToAndClick("Appearance")
        app.waitForText("Electric")
        pause()
        screencap("06_appearance")

        // Flip to dark mode in-app, then back to the inbox for the dark shot (Settings is a pushed
        // screen without the home tabs, so return with back, not the "Chats" tab)
        app.onNodeWithText("On").performClick()
        app.waitForIdle()
        pause()
        app.pressBack()
        app.waitForText("App lock")
        app.pressBack()
        app.waitForText("Mom")
        pause()
        screencap("07_inbox_dark")
    }

    private fun startDemo() {
        app.waitForText("Try demo mode")
        app.onNodeWithText("Try demo mode").assertIsDisplayed()
        app.onNodeWithText("Try demo mode").performClick()
        app.waitForText("Lock HowYouDoin?")
        app.onNodeWithText("Not now").performClick()
        app.clickIfShown("Skip")
        app.waitForText("Mom")
        app.skipTour()
    }

    private fun screencap(name: String) {
        app.waitForIdle()
        shell("screencap -p $DIR/$name.png")
    }

    private fun pause() {
        app.waitForIdle()
        Thread.sleep(SETTLE_MILLIS)
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }

    private companion object {
        const val DIR = "/sdcard/hyd_shots"

        /** Long enough for animations and async loads to settle before the shot. */
        const val SETTLE_MILLIS = 1_500L
    }
}
