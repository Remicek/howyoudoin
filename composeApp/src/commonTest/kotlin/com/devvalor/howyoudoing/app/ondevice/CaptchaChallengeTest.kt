package com.devvalor.howyoudoing.app.ondevice

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptchaChallengeTest {

    @Test
    fun readsTheCaptchaFromTheNativeError() {
        val challenge = captchaChallenge(
            ChatNetwork.DISCORD,
            """captcha_required:{"pageUrl":"https://discord.com/","rqData":"data","service":"hcaptcha","siteKey":"site"}"""
        )!!
        assertEquals("site", challenge.siteKey)
        assertEquals("data", challenge.rqData)
        assertEquals("https://discord.com/", challenge.pageUrl)
        assertEquals(ChatNetwork.DISCORD, challenge.network)
    }

    @Test
    fun otherErrorsAreNoCaptcha() {
        assertNull(captchaChallenge(ChatNetwork.DISCORD, "HTTP 403 Forbidden"))
        assertNull(captchaChallenge(ChatNetwork.DISCORD, null))
        assertNull(captchaChallenge(ChatNetwork.DISCORD, "captcha_required:not json"))
    }
}
