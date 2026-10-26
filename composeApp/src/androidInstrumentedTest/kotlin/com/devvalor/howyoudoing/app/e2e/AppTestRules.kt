package com.devvalor.howyoudoing.app.e2e

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.printToLog
import android.os.Build
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.devvalor.howyoudoing.app.MainActivity
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

typealias AppRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

/**
 * Starts [MainActivity] in [locale] with notifications allowed (so the system prompt doesn't cover the app).
 * The orchestrator clears the app's data before every test, so each one starts at the welcome screen.
 */
class AppTestRules(locale: String = "en-US") {
    val app: AppRule = createAndroidComposeRule<MainActivity>()

    val chain: TestRule = RuleChain
        .outerRule(shell("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS", minSdk = Build.VERSION_CODES.TIRAMISU))
        .around(shell("cmd locale set-app-locales $PACKAGE --locales $locale", minSdk = Build.VERSION_CODES.TIRAMISU))
        .around(app)

    private fun shell(command: String, minSdk: Int) = object : ExternalResource() {
        override fun before() {
            if (Build.VERSION.SDK_INT < minSdk) return
            // Reading the output waits for the command to finish
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
                .use { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes() }
        }
    }

    companion object {
        val PACKAGE: String = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    }
}

private const val TIMEOUT_MILLIS = 15_000L

/** Waits until a node with [text] is on screen (the app loads asynchronously at start and after navigating). */
fun AppRule.waitForText(text: String, substring: Boolean = false, timeoutMillis: Long = TIMEOUT_MILLIS) {
    waitForNode(hasText(text, substring = substring), timeoutMillis)
}

fun AppRule.waitForNode(matcher: SemanticsMatcher, timeoutMillis: Long = TIMEOUT_MILLIS) {
    try {
        waitUntil(timeoutMillis) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: ComposeTimeoutException) {
        // What was on screen instead, in logcat under "E2E"
        runCatching { onAllNodes(isRoot()).onFirst().printToLog("E2E") }
        throw AssertionError("Nothing matching ${matcher.description} appeared within $timeoutMillis ms", e)
    }
}

/** Taps [text] if it's showing (for prompts that appear only sometimes, like the backup offer). */
fun AppRule.clickIfShown(text: String) {
    waitForIdle()
    if (onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) onNodeWithText(text).performClick()
}

/** The system back button, as the user presses it. */
fun AppRule.pressBack() {
    activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
}

/** Scrolls the screen's list to [text] (lazy lists only compose what's visible) and taps it. */
fun AppRule.scrollToAndClick(text: String) {
    val list = onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes()
    if (list.isNotEmpty()) onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
    onNodeWithText(text).performClick()
}
