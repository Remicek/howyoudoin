package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.data.matrix.dto.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Someone on Matrix: from the user directory, or an exact Matrix ID looked up. */
data class MatrixPerson(val userId: String, val displayName: String?, val avatarUrl: String?)

private val MATRIX_USER_ID = Regex("""^@[^:\s]+:\S+$""")

/** A full Matrix ID ("@name:server"), which can be looked up directly. */
fun isMatrixUserId(query: String): Boolean = MATRIX_USER_ID.matches(query.trim())

/**
 * A typed Matrix ID as the server knows it: Matrix IDs are lowercase (server names are DNS names, and user names
 * can only be lowercase), while phone keyboards capitalise ("@remicek:Matrix.org").
 */
fun normalizeMatrixUserId(query: String): String = query.trim().lowercase()

/** The people in a `/user_directory/search` response. */
fun JsonObject.directoryResults(): List<MatrixPerson> =
    (this["results"] as? JsonArray).orEmpty().mapNotNull { result ->
        val person = result as? JsonObject ?: return@mapNotNull null
        MatrixPerson(
            userId = person.string("user_id") ?: return@mapNotNull null,
            displayName = person.string("display_name")?.takeIf { it.isNotBlank() },
            avatarUrl = person.string("avatar_url")?.takeIf { it.isNotBlank() }
        )
    }

/** The person a `/profile/{userId}` response describes. */
fun JsonObject.toPerson(userId: String) = MatrixPerson(
    userId = userId,
    displayName = string("displayname")?.takeIf { it.isNotBlank() },
    avatarUrl = string("avatar_url")?.takeIf { it.isNotBlank() }
)

/**
 * The `/createRoom` request for a one-to-one chat with [userId]: private, marked as direct, and end-to-end encrypted
 * when this device can encrypt (a room's encryption can't be turned off later, and an encrypted room this device
 * can't encrypt for would be one it can't write in).
 */
fun directRoomRequest(userId: String, encrypted: Boolean): JsonObject = buildJsonObject {
    put("preset", "trusted_private_chat")
    put("is_direct", true)
    putJsonArray("invite") { add(JsonPrimitive(userId)) }
    if (encrypted) {
        putJsonArray("initial_state") {
            addJsonObject {
                put("type", "m.room.encryption")
                put("state_key", "")
                putJsonObject("content") { put("algorithm", "m.megolm.v1.aes-sha2") }
            }
        }
    }
}

/** `m.direct` account data (user ID → their direct rooms) with [roomId] added for [userId]. */
fun JsonObject.withDirectRoom(userId: String, roomId: String): JsonObject {
    val rooms = (this[userId] as? JsonArray).orEmpty()
    if (rooms.any { (it as? JsonPrimitive)?.content == roomId }) return this
    return JsonObject(this + (userId to JsonArray(rooms + JsonPrimitive(roomId))))
}

/** The other person of an unnamed one-to-one room (joined or invited), or null for groups and named rooms. */
fun RoomCache.directPartner(myUserId: String): String? {
    if (!stateEvent("m.room.name")?.content?.string("name").isNullOrBlank()) return null
    val members = state.values
        .filter { it.type == "m.room.member" && it.content.string("membership") in ACTIVE_MEMBERSHIPS }
        .mapNotNull { it.stateKey }
    val others = (heroes + members).filter { it != myUserId }.distinct()
    return others.singleOrNull()?.takeIf { joinedMemberCount <= 2 }
}

private val ACTIVE_MEMBERSHIPS = setOf("join", "invite")
