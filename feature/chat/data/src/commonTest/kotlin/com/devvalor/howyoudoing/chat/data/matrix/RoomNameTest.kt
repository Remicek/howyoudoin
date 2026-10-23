package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.data.mappers.toChat
import com.devvalor.howyoudoing.chat.data.matrix.dto.MatrixEventDto
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomNameTest {

    private fun member(userId: String, membership: String, name: String) = MatrixEventDto(
        type = "m.room.member",
        stateKey = userId,
        sender = userId,
        content = buildJsonObject {
            put("membership", JsonPrimitive(membership))
            put("displayname", JsonPrimitive(name))
        }
    )

    private fun room(otherMembership: String) = RoomCache(
        roomId = "!r:example.org",
        state = listOf(member(ME, "join", "Me"), member(OTHER, otherMembership, "Remik 1")).associateBy { it.stateCacheKey },
        heroes = listOf(OTHER)
    )

    @Test
    fun namedAfterTheOtherPerson() = assertEquals("Remik 1", room("join").toChat(ME).name)

    @Test
    fun aChatTheyHaventJoinedSaysSo() = assertEquals("Remik 1 (invited)", room("invite").toChat(ME).name)

    @Test
    fun aChatTheyLeftSaysSo() = assertEquals("Remik 1 (left)", room("leave").toChat(ME).name)

    private companion object {
        const val ME = "@me:example.org"
        const val OTHER = "@other:example.org"
    }
}
