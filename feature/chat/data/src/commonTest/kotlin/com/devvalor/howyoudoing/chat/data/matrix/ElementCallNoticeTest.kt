package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.data.mappers.toMessages
import com.devvalor.howyoudoing.chat.data.matrix.dto.MatrixEventDto
import com.devvalor.howyoudoing.chat.domain.MessageType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElementCallNoticeTest {

    private fun callMember(id: String, sender: String, joined: Boolean) = MatrixEventDto(
        type = "org.matrix.msc3401.call.member",
        eventId = id,
        sender = sender,
        originServerTs = 1L,
        stateKey = "_${sender}_DEVICE_m.call",
        content = if (joined) buildJsonObject {
            put(
                "application",
                JsonPrimitive("m.call")
            )
        } else JsonObject(emptyMap())
    )

    @Test
    fun someoneStartingAnElementCallShowsANotice() {
        val messages = RoomCache("!r:x", timeline = listOf(callMember("\$1", OTHER, joined = true))).toMessages(ME)
        assertEquals(MessageType.NOTICE, messages.single().type)
        assertTrue("Element" in messages.single().body)
    }

    @Test
    fun leavingTheCallOrOurOwnCallIsNotShown() {
        val timeline = listOf(callMember("\$1", OTHER, joined = false), callMember("\$2", ME, joined = true))
        assertTrue(RoomCache("!r:x", timeline = timeline).toMessages(ME).isEmpty())
    }

    @Test
    fun oneCallIsAnnouncedOnce() {
        val ringing = MatrixEventDto(
            type = "org.matrix.msc4075.rtc.notification", eventId = "\$2", sender = OTHER, originServerTs = 2L,
            content = buildJsonObject { put("notification_type", JsonPrimitive("ring")) }
        )
        val timeline = listOf(callMember("\$1", OTHER, joined = true), ringing)
        assertEquals(1, RoomCache("!r:x", timeline = timeline).toMessages(ME).size)
    }

    private companion object {
        const val ME = "@me:x"
        const val OTHER = "@other:x"
    }
}
