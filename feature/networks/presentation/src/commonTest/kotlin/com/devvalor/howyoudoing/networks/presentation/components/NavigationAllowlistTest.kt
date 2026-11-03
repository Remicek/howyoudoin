package com.devvalor.howyoudoing.networks.presentation.components

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationAllowlistTest {

    private val meta = cookieLoginAllowedHosts(ChatNetwork.MESSENGER)

    @Test
    fun exactHostIsAllowed() {
        assertTrue(isNavigationAllowed("https://messenger.com/login", meta))
    }

    @Test
    fun subdomainOfAnAllowedHostIsAllowed() {
        assertTrue(isNavigationAllowed("https://www.messenger.com/", meta))
        assertTrue(isNavigationAllowed("https://m.facebook.com/login.php", meta))
    }

    @Test
    fun anUnrelatedHostIsBlocked() {
        assertFalse(isNavigationAllowed("https://evil.example.com/messenger.com", meta))
    }

    @Test
    fun aLookalikeSuffixIsNotTreatedAsASubdomain() {
        // "notfacebook.com" ends with "facebook.com" as text but is a different registrable domain.
        assertFalse(isNavigationAllowed("https://notfacebook.com/", meta))
    }

    @Test
    fun userinfoCannotSmuggleAnAllowedHost() {
        assertFalse(isNavigationAllowed("https://messenger.com@evil.example.com/", meta))
    }

    @Test
    fun portIsIgnoredWhenMatching() {
        assertTrue(isNavigationAllowed("https://messenger.com:443/login", meta))
    }

    @Test
    fun nonHttpSchemesAreRefusedWhenPinned() {
        assertFalse(isNavigationAllowed("data:text/html,<h1>Log in to Messenger</h1>", meta))
        assertFalse(isNavigationAllowed("javascript:alert(1)", meta))
        assertFalse(isNavigationAllowed("file:///sdcard/login.html", meta))
    }

    @Test
    fun aBackslashCannotSmuggleAnAllowedHost() {
        // Browsers end the authority at '\' for http(s), so this loads evil.example.
        assertFalse(isNavigationAllowed("https://evil.example\\@messenger.com/", meta))
    }

    @Test
    fun userinfoRunsToTheLastAt() {
        assertFalse(isNavigationAllowed("https://messenger.com@x@evil.example.com/", meta))
        assertTrue(isNavigationAllowed("https://evil.example@x@www.messenger.com/", meta))
    }

    @Test
    fun schemeCaseAndSurroundingWhitespaceDoNotBypassTheCheck() {
        assertFalse(isNavigationAllowed("HTTPS://evil.example.com/", meta))
        assertFalse(isNavigationAllowed("  https://evil.example.com/", meta))
        assertTrue(isNavigationAllowed("HTTPS://www.messenger.com/", meta))
    }

    @Test
    fun anEmptyAllowlistPermitsAnyHost() {
        val slack = cookieLoginAllowedHosts(ChatNetwork.SLACK)
        assertTrue(slack.isEmpty())
        assertTrue(isNavigationAllowed("https://anything.example.com/", slack))
    }

    @Test
    fun bothGoogleLoginsPinTheSameGoogleHosts() {
        val voice = cookieLoginAllowedHosts(ChatNetwork.GOOGLE_VOICE)
        val messages = cookieLoginAllowedHosts(ChatNetwork.GOOGLE_MESSAGES)
        assertEquals(voice, messages)
        assertTrue(isNavigationAllowed("https://accounts.google.com/signin", voice))
        assertTrue(isNavigationAllowed("https://voice.google.com/u/0/messages", voice))
    }

    @Test
    fun hostOfParsesTheRegistrableAuthority() {
        assertEquals("x.com", hostOf("https://x.com/i/flow/login?lang=en"))
        assertEquals("accounts.google.com", hostOf("https://accounts.google.com:443/signin#frag"))
    }
}
