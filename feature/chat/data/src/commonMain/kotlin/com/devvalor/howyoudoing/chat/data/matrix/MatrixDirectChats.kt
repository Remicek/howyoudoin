package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.data.matrix.dto.string
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Finding people on Matrix and starting one-to-one chats with them, for the account [engine] syncs. */
class MatrixDirectChats(
    private val api: MatrixApi,
    private val engine: MatrixSyncEngine
) {

    /**
     * People matching [query] in the user directory. A full Matrix ID is also looked up directly: the directory
     * (on matrix.org, for one) only lists people who share a room with the user or are in public rooms.
     */
    suspend fun searchPeople(query: String): Result<List<MatrixPerson>, DataError.Remote> {
        val session = engine.session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        val term = query.trim()
        if (term.isEmpty()) return Result.Success(emptyList())
        val exact = if (isMatrixUserId(term)) lookUp(session, normalizeMatrixUserId(term)) else null
        val directory = api.searchUserDirectory(session, term, SEARCH_LIMIT)
        return when {
            directory is Result.Failure && exact == null -> directory
            else -> Result.Success(
                (listOfNotNull(exact) + (directory as? Result.Success)?.data?.directoryResults().orEmpty())
                    .distinctBy { it.userId }
                    .filter { it.userId != session.userId }
            )
        }
    }

    /** The person with this exact Matrix ID; null when there's no such person. */
    private suspend fun lookUp(session: AuthInfo, userId: String): MatrixPerson? =
        when (val profile = api.profile(session, userId)) {
            is Result.Success -> profile.data.toPerson(userId)
            // Anything but "not found" (a server that hides profiles, say) still lets the user try
            is Result.Failure -> MatrixPerson(userId, displayName = null, avatarUrl = null)
                .takeIf { profile.error != DataError.Remote.NOT_FOUND }
        }

    /** The one-to-one room with [userId] already here, if any. */
    fun directRoomWith(userId: String): String? {
        val myUserId = engine.session.value?.userId ?: return null
        return engine.rooms.value.values.firstOrNull { it.directPartner(myUserId) == userId }?.roomId
    }

    /**
     * Opens a one-to-one chat with [userId]: the one already here, or a new room (encrypted when this device can
     * encrypt) inviting them. Returns the room's ID once it's synced, or after a while if it isn't yet.
     */
    suspend fun startDirectChat(userId: String): Result<String, DataError.Remote> {
        val session = engine.session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        return directRoomWith(userId)?.let { Result.Success(it) } ?: createDirectRoom(session, userId)
    }

    private suspend fun createDirectRoom(session: AuthInfo, userId: String): Result<String, DataError.Remote> {
        val encrypted = engine.canEncrypt
        val created = api.createRoom(session, directRoomRequest(userId, encrypted))
        val roomId = (created as? Result.Success)?.data?.string("room_id")
            ?: return (created as? Result.Failure) ?: Result.Failure(DataError.Remote.SERIALIZATION)
        if (encrypted) engine.rememberEncrypted(roomId)
        markDirect(session, userId, roomId)
        withTimeoutOrNull(NEW_ROOM_TIMEOUT_MILLIS) { engine.rooms.first { roomId in it } }
        return Result.Success(roomId)
    }

    /** Lists the room in `m.direct`, so other apps show it as the direct chat with [userId] too. */
    private suspend fun markDirect(session: AuthInfo, userId: String, roomId: String) {
        val current = when (val data = api.accountData(session, DIRECT_ACCOUNT_DATA)) {
            is Result.Success -> data.data
            is Result.Failure -> JsonObject(emptyMap()).takeIf { data.error == DataError.Remote.NOT_FOUND }
        } ?: return
        api.setAccountData(session, DIRECT_ACCOUNT_DATA, current.withDirectRoom(userId, roomId))
    }

    private companion object {
        const val SEARCH_LIMIT = 20
        const val NEW_ROOM_TIMEOUT_MILLIS = 10_000L
        const val DIRECT_ACCOUNT_DATA = "m.direct"
    }
}
