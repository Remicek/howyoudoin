package com.devvalor.howyoudoing.core.domain.ondevice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelegramApiKeysTest {

    @Test
    fun keysAsCopiedFromMyTelegramOrgAreAccepted() {
        assertEquals(
            OwnTelegramKeys(1234567, "0123456789abcdef0123456789abcdef"),
            TelegramApiKeys.parse(" 1234567 ", " 0123456789ABCDEF0123456789abcdef\n")
        )
    }

    @Test
    fun anythingElseIsRefused() {
        assertNull(TelegramApiKeys.parse("", "0123456789abcdef0123456789abcdef"))
        assertNull(TelegramApiKeys.parse("-5", "0123456789abcdef0123456789abcdef"))
        assertNull(TelegramApiKeys.parse("12", "0123456789abcdef"))
        assertNull(TelegramApiKeys.parse("12", "0123456789abcdef0123456789abcdeg"))
    }
}
