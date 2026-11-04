package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.data.matrix.dto.MatrixEventDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MatrixPeopleTest {

    @Test
    fun aFullMatrixIdIsRecognised() {
        assertTrue(isMatrixUserId("@remicek:matrix.org"))
        assertTrue(isMatrixUserId("  @a.b-c_d:example.com:8448 "))
        assertFalse(isMatrixUserId("remicek"))
        assertFalse(isMatrixUserId("@remicek"))
        assertFalse(isMatrixUserId("remicek:matrix.org"))
        assertFalse(isMatrixUserId("@remi cek:matrix.org"))
    }

    @Test
    fun aTypedIdIsLowercased() = assertEquals("@remicek:matrix.org", normalizeMatrixUserId(" @remicek:Matrix.org "))

    @Test
    fun directoryResultsAreRead() {
        val response = Json.parseToJsonElement(
            """{"limited":false,"results":[
                {"user_id":"@a:x.org","display_name":"Ann","avatar_url":"mxc://x.org/a"},
                {"user_id":"@b:x.org","display_name":""},
                {"display_name":"no id"}
            ]}"""
        ).jsonObject
        assertEquals(
            listOf(MatrixPerson("@a:x.org", "Ann", "mxc://x.org/a"), MatrixPerson("@b:x.org", null, null)),
            response.directoryResults()
        )
        assertEquals(emptyList(), JsonObject(emptyMap()).directoryResults())
    }

    @Test
    fun aProfileBecomesThePerson() = assertEquals(
        MatrixPerson("@a:x.org", "Ann", null),
        buildJsonObject { put("displayname", JsonPrimitive("Ann")) }.toPerson("@a:x.org")
    )

    @Test
    fun aDirectRoomInvitesThePersonAndIsEncryptedWhenItCanBe() {
        val encrypted = directRoomRequest("@b:x.org", encrypted = true)
        assertEquals("trusted_private_chat", encrypted["preset"]?.jsonPrimitive?.content)
        assertEquals("true", encrypted["is_direct"]?.jsonPrimitive?.content)
        assertEquals(listOf("@b:x.org"), encrypted["invite"]!!.jsonArray.map { it.jsonPrimitive.content })
        val state = encrypted["initial_state"]!!.jsonArray.single().jsonObject
        assertEquals("m.room.encryption", state["type"]?.jsonPrimitive?.content)
        assertEquals("m.megolm.v1.aes-sha2", state["content"]!!.jsonObject["algorithm"]?.jsonPrimitive?.content)

        assertNull(directRoomRequest("@b:x.org", encrypted = false)["initial_state"])
    }

    @Test
    fun theRoomIsAddedToTheDirectChats() {
        val before = buildJsonObject {
            put("@b:x.org", JsonArray(listOf(JsonPrimitive("!old:x.org"))))
            put("@c:x.org", JsonArray(listOf(JsonPrimitive("!c:x.org"))))
        }
        val after = before.withDirectRoom("@b:x.org", "!new:x.org")
        assertEquals(listOf("!old:x.org", "!new:x.org"), after["@b:x.org"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(before["@c:x.org"], after["@c:x.org"])
        assertEquals(
            listOf("!d:x.org"),
            JsonObject(emptyMap()).withDirectRoom("@d:x.org", "!d:x.org")["@d:x.org"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        )
        assertSame(after, after.withDirectRoom("@b:x.org", "!new:x.org"))
    }

    private fun member(userId: String, membership: String) = MatrixEventDto(
        type = "m.room.member",
        stateKey = userId,
        sender = userId,
        content = buildJsonObject { put("membership", JsonPrimitive(membership)) }
    )

    private fun room(vararg members: MatrixEventDto, name: String? = null, joined: Int = 0) = RoomCache(
        roomId = "!r:x.org",
        state = (members.toList() + listOfNotNull(name?.let {
            MatrixEventDto(type = "m.room.name", stateKey = "", sender = ME, content = buildJsonObject {
                put("name", JsonPrimitive(it))
            })
        })).associateBy { it.stateCacheKey },
        joinedMemberCount = joined
    )

    @Test
    fun theOtherPersonOfAOneToOneRoom() {
        assertEquals("@b:x.org", room(member(ME, "join"), member("@b:x.org", "join"), joined = 2).directPartner(ME))
        // Invited and not joined yet: still the chat with them
        assertEquals("@b:x.org", room(member(ME, "join"), member("@b:x.org", "invite"), joined = 1).directPartner(ME))
    }

    @Test
    fun groupsNamedRoomsAndRoomsTheyLeftAreNotTheirChat() {
        assertNull(
            room(member(ME, "join"), member("@b:x.org", "join"), member("@c:x.org", "join"), joined = 3)
                .directPartner(ME)
        )
        assertNull(room(member(ME, "join"), member("@b:x.org", "join"), name = "Team", joined = 2).directPartner(ME))
        assertNull(room(member(ME, "join"), member("@b:x.org", "leave"), joined = 1).directPartner(ME))
    }

    private companion object {
        const val ME = "@me:x.org"
    }
}
