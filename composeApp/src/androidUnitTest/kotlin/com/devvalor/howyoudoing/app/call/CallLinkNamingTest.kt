package com.devvalor.howyoudoing.app.call

import com.devvalor.howyoudoing.chat.data.linkcall.CallLinkNaming
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The call's name must come out the same here and on the call page (call-link/test/link.test.ts has this vector). */
class CallLinkNamingTest {
    @Test
    fun theCallIsNamedLikeThePageNamesIt() {
        val key = ByteArray(CallLinkNaming.KEY_BYTES) { 1 }
        assertEquals("AQEBAQEBAQEBAQEBAQEBAQ", CallLinkNaming.encode(key))
        val room = CallLinkNaming.roomOf(key) { MessageDigest.getInstance("SHA-256").digest(it) }
        assertEquals("zIzUHO-QfE0hYGkSLEuJk2", room)
    }
}
