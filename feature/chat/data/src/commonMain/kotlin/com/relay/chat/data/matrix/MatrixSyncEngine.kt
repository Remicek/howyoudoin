package com.relay.chat.data.matrix

import com.relay.chat.data.matrix.dto.InvitedRoomDto
import com.relay.chat.data.matrix.dto.SyncResponseDto
import com.relay.chat.data.matrix.dto.string
import com.relay.chat.domain.SyncState
import com.relay.core.domain.auth.AuthInfo
import com.relay.core.domain.auth.SessionStorage
import com.relay.core.domain.network.ChatNetwork
import com.relay.core.domain.util.DataError
import com.relay.core.domain.util.EmptyResult
import com.relay.core.domain.util.Result
import com.relay.core.domain.util.asEmptyResult
import com.relay.core.domain.util.onFailure
import com.relay.core.domain.util.onSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Keeps a /sync long-poll running for the current (non-demo) Matrix session and exposes
 * joined rooms as an in-memory cache. Bridged WhatsApp / Messenger chats are just Matrix
 * rooms here; the mappers work out which network a room belongs to.
 *
 * TODO: persist the cache + next_batch token so cold starts don't need a full initial sync.
 * TODO: end-to-end encryption (bridges can run with E2EE enabled), needs matrix-rust-sdk/vodozemac.
 */
@OptIn(ExperimentalTime::class)
class MatrixSyncEngine(
    private val api: MatrixApi,
    private val sessionStorage: SessionStorage,
    private val applicationScope: CoroutineScope
) {

    private val _rooms = MutableStateFlow<Map<String, RoomCache>>(emptyMap())
    val rooms: StateFlow<Map<String, RoomCache>> = _rooms.asStateFlow()

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _session = MutableStateFlow<AuthInfo?>(null)
    val session: StateFlow<AuthInfo?> = _session.asStateFlow()

    private val attemptedInvites = mutableSetOf<String>()

    private val syncJob by lazy {
        applicationScope.launch {
            sessionStorage.observeAuthInfo()
                .map { it?.takeUnless { session -> session.isDemo } }
                .distinctUntilChanged()
                .collectLatest { session ->
                    _rooms.value = emptyMap()
                    attemptedInvites.clear()
                    _session.value = session
                    if (session == null) {
                        _syncState.value = SyncState.IDLE
                    } else {
                        runSyncLoop(session)
                    }
                }
        }
    }

    fun ensureStarted() {
        syncJob
    }

    private suspend fun runSyncLoop(session: AuthInfo) {
        var since: String? = null
        var backoffMillis = INITIAL_BACKOFF_MILLIS
        while (currentCoroutineContext().isActive) {
            if (since == null) _syncState.value = SyncState.CONNECTING
            val result = api.sync(
                session = session,
                since = since,
                timeoutMillis = if (since == null) 0L else LONG_POLL_MILLIS
            )
            when (result) {
                is Result.Success -> {
                    applySync(session, result.data)
                    since = result.data.nextBatch
                    backoffMillis = INITIAL_BACKOFF_MILLIS
                    _syncState.value = SyncState.SYNCED
                }
                is Result.Failure -> {
                    if (result.error == DataError.Remote.UNAUTHORIZED) {
                        // Token revoked or expired, MainViewModel routes back to login.
                        sessionStorage.set(null)
                        return
                    }
                    _syncState.value = SyncState.OFFLINE
                    delay(backoffMillis)
                    backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
                }
            }
        }
    }

    private suspend fun applySync(session: AuthInfo, sync: SyncResponseDto) {
        val rooms = sync.rooms ?: return
        _rooms.update { current ->
            val updated = current.toMutableMap()
            rooms.leave.keys.forEach(updated::remove)
            rooms.join.forEach { (roomId, room) ->
                updated[roomId] = (updated[roomId] ?: RoomCache(roomId)).merge(room)
            }
            updated
        }
        autoJoinBridgeInvites(session, rooms.invite)
    }

    /**
     * Bridges create a portal room per chat and invite us. Without double puppeting those
     * invites must be accepted by the client, so accept anything a bridge ghost/bot sends.
     */
    private suspend fun autoJoinBridgeInvites(session: AuthInfo, invites: Map<String, InvitedRoomDto>) {
        invites.forEach { (roomId, invite) ->
            if (!attemptedInvites.add(roomId)) return@forEach
            val inviter = invite.inviteState?.events
                ?.firstOrNull { it.type == "m.room.member" && it.stateKey == session.userId }
                ?.takeIf { it.content.string("membership") == "invite" }
                ?.sender
                ?: return@forEach
            if (ChatNetwork.fromUserId(inviter) != null) {
                api.joinRoom(session, roomId)
            }
        }
    }

    suspend fun loadOlder(roomId: String): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        val room = _rooms.value[roomId] ?: return Result.Success(Unit)
        val from = room.prevBatch
        if (from == null || !room.hasMoreHistory) {
            updateRoom(roomId) { it.copy(hasMoreHistory = false) }
            return Result.Success(Unit)
        }
        return api.messages(session, roomId, from)
            .onSuccess { response -> updateRoom(roomId) { it.prependHistory(response) } }
            .asEmptyResult()
    }

    suspend fun sendText(roomId: String, body: String): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        val now = Clock.System.now().toEpochMilliseconds()
        val transactionId = "relay-$now-${Random.nextInt(0, Int.MAX_VALUE)}"
        updateRoom(roomId) {
            it.copy(pending = it.pending + PendingMessage(transactionId, body, now))
        }
        return api.sendText(session, roomId, transactionId, body)
            .onSuccess { response ->
                updatePending(roomId, transactionId) { it.copy(eventId = response.eventId) }
            }
            .onFailure {
                updatePending(roomId, transactionId) { it.copy(isFailed = true) }
            }
            .asEmptyResult()
    }

    suspend fun markAsRead(roomId: String): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        val room = _rooms.value[roomId] ?: return Result.Success(Unit)
        if (room.unreadCount == 0) return Result.Success(Unit)
        val lastEventId = room.timeline.lastOrNull { it.eventId != null }?.eventId
            ?: return Result.Success(Unit)
        updateRoom(roomId) { it.copy(unreadCount = 0) }
        return api.sendReadReceipt(session, roomId, lastEventId).asEmptyResult()
    }

    private fun updateRoom(roomId: String, transform: (RoomCache) -> RoomCache) {
        _rooms.update { rooms ->
            val room = rooms[roomId] ?: return@update rooms
            rooms + (roomId to transform(room))
        }
    }

    private fun updatePending(roomId: String, transactionId: String, transform: (PendingMessage) -> PendingMessage) {
        updateRoom(roomId) { room ->
            room.copy(pending = room.pending.map { if (it.transactionId == transactionId) transform(it) else it })
        }
    }

    private companion object {
        const val LONG_POLL_MILLIS = 30_000L
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
    }
}
