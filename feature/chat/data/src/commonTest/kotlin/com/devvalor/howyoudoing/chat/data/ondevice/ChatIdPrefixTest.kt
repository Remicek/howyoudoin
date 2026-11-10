package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChatIdPrefixTest {

    @Test
    fun firstAccountsKeepTheirPrefixes() {
        assertEquals("wadevice:", OnDeviceMessengerStore.chatIdPrefix(ChatNetwork.WHATSAPP, 0))
        assertEquals("fbdevice:", OnDeviceMessengerStore.chatIdPrefix(ChatNetwork.MESSENGER, 0))
        assertEquals("blueskydevice:", OnDeviceMessengerStore.chatIdPrefix(ChatNetwork.BLUESKY, 0))
    }

    @Test
    fun otherAccountsAreNumbered() {
        assertEquals("wadevice2:", OnDeviceMessengerStore.chatIdPrefix(ChatNetwork.WHATSAPP, 1))
        assertEquals("googlemessagesdevice3:", OnDeviceMessengerStore.chatIdPrefix(ChatNetwork.GOOGLE_MESSAGES, 2))
        // A chat of the second account is never taken for the first's
        assertFalse("wadevice2:123".startsWith(OnDeviceMessengerStore.chatIdPrefix(ChatNetwork.WHATSAPP, 0)))
    }
}
