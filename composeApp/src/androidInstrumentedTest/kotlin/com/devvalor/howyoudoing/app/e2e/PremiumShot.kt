package com.devvalor.howyoudoing.app.e2e

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.devvalor.howyoudoing.core.designsystem.theme.HowYouDoinTheme
import com.devvalor.howyoudoing.core.domain.premium.CallMinutes
import com.devvalor.howyoudoing.core.domain.premium.PremiumOffer
import com.devvalor.howyoudoing.core.domain.premium.PremiumStore
import com.devvalor.howyoudoing.settings.presentation.premium.PremiumScreen
import com.devvalor.howyoudoing.settings.presentation.premium.PremiumState
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Premium screen for the store screenshots, as someone sees it on a phone where Google Play sells it: the real
 * screen with the listing's price and trial. Demo mode can't show this (an emulator without a Google account gets
 * "Google Play isn't offering Premium"). Runs only with -Pandroid.testInstrumentationRunnerArguments.demoShots=true,
 * and writes /sdcard/hyd_shots/08_premium.png.
 */
@RunWith(AndroidJUnit4::class)
class PremiumShot {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("demoShots") == "true")
        val state = PremiumState(
            subscription = PremiumOffer(price = "$2.99", trialDays = 7),
            hasCalls = true,
            minutes = CallMinutes(
                isPremium = false,
                videoLeftMs = 42 * MINUTE,
                audioLeftMs = 9 * HOUR + 20 * MINUTE,
                resetsAtMs = System.currentTimeMillis() + 20 * DAY,
            ),
            store = PremiumStore.READY,
        )
        compose.setContent { HowYouDoinTheme(darkTheme = false) { PremiumScreen(state) {} } }
        compose.waitForIdle()
        Thread.sleep(1_500)
        shell("mkdir -p $DIR")
        shell("screencap -p $DIR/08_premium.png")
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }

    private companion object {
        const val DIR = "/sdcard/hyd_shots"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
