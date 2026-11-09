package com.devvalor.howyoudoing.chat.data.matrix

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatrixInvitesTest {

    private val me = "@me:hyd.example"

    @Test
    fun a_bridge_ghost_on_our_server_is_joined() {
        assertTrue(shouldAutoJoin("@whatsapp_4812345:hyd.example", me, isDirect = false, acceptDirectInvites = false))
        assertTrue(shouldAutoJoin("@whatsapp_4812345:HYD.example", me, isDirect = false, acceptDirectInvites = false))
    }

    @Test
    fun a_look_alike_ghost_on_another_server_is_not_joined() {
        assertFalse(shouldAutoJoin("@whatsapp_4812345:evil.example", me, isDirect = false, acceptDirectInvites = false))
        assertFalse(shouldAutoJoin("@whatsapp_4812345:evil.example", me, isDirect = true, acceptDirectInvites = false))
    }

    @Test
    fun a_person_is_joined_only_for_a_direct_chat_when_allowed() {
        assertFalse(shouldAutoJoin("@alice:other.example", me, isDirect = true, acceptDirectInvites = false))
        assertFalse(shouldAutoJoin("@alice:other.example", me, isDirect = false, acceptDirectInvites = true))
        assertTrue(shouldAutoJoin("@alice:other.example", me, isDirect = true, acceptDirectInvites = true))
    }

    @Test
    fun an_invite_without_a_sender_is_not_joined() {
        assertFalse(shouldAutoJoin(null, me, isDirect = true, acceptDirectInvites = true))
    }

    @Test
    fun only_a_json_true_marks_a_direct_chat() {
        assertTrue(isDirectInvite(buildJsonObject { put("is_direct", true) }))
        assertFalse(isDirectInvite(buildJsonObject { put("is_direct", "true") }))
        assertFalse(isDirectInvite(buildJsonObject { put("is_direct", false) }))
        assertFalse(isDirectInvite(buildJsonObject { put("is_direct", JsonPrimitive(1)) }))
        assertFalse(isDirectInvite(buildJsonObject { }))
        assertFalse(isDirectInvite(null))
    }
}
