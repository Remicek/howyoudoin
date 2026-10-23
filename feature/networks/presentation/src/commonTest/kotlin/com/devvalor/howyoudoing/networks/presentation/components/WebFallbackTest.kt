package com.devvalor.howyoudoing.networks.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebFallbackTest {

    @Test
    fun slackMagicLoginOpensTheWorkspaceWebClient() {
        assertEquals(
            "https://app.slack.com/client/T0CCF7R9F08",
            webFallbackFor("slack://T0CCF7R9F08/magic-login/123-abc?id=1")
        )
    }

    @Test
    fun otherAppLinksStayPut() {
        assertNull(webFallbackFor("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(webFallbackFor("fb-messenger://user/1"))
    }
}
