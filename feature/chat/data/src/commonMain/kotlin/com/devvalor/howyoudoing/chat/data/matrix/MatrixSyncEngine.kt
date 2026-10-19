package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.chat.data.matrix.dto.InvitedRoomDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.SendEventResponseDto
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import com.devvalor.howyoudoing.chat.data.matrix.dto.SyncResponseDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.string
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.auth.SessionStorage
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.domain.util.asEmptyResult
import com.devvalor.howyoudoing.core.domain.util.onFailure
import com.devvalor.howyoudoing.core.domain.util.onSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.getAndUpdate
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

    /** Bumped to restart the sync loop with a fresh initial sync (pull to refresh). */
    private val refreshRequests = MutableStateFlow(0)
    private val refreshWaiters = MutableStateFlow<List<CompletableDeferred<Unit>>>(emptyList())

    private val syncJob by lazy {
        applicationScope.launch {
            sessionStorage.observeAuthInfo()
                .map { it?.takeIf { session -> session.hasServer } }
                .distinctUntilChanged()
                .collectLatest { session ->
                    _rooms.value = emptyMap()
                    attemptedInvites.clear()
                    _session.value = session
                    if (session == null) {
                        _syncState.value = SyncState.IDLE
                    } else {
                        // Each refresh request cancels the running long-poll and starts over
                        refreshRequests.collectLatest { runSyncLoop(session) }
                    }
                }
        }
    }

    fun ensureStarted() {
        syncJob
    }

    /**
     * Re-fetches everything with an initial sync, replacing the cached rooms; recovers rooms or state an
     * incremental sync missed. Returns once that sync finished (or failed, or took too long).
     */
    suspend fun refresh() {
        ensureStarted()
        val done = CompletableDeferred<Unit>()
        refreshWaiters.update { it + done }
        refreshRequests.update { it + 1 }
        withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { done.await() }
    }

    private fun completeRefreshWaiters() {
        refreshWaiters.getAndUpdate { emptyList() }.forEach { it.complete(Unit) }
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
                    applySync(session, result.data, isInitial = since == null)
                    if (since == null) completeRefreshWaiters()
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
                    completeRefreshWaiters()
                    delay(backoffMillis)
                    backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
                }
            }
        }
    }

    private suspend fun applySync(session: AuthInfo, sync: SyncResponseDto, isInitial: Boolean) {
        val rooms = sync.rooms ?: run {
            if (isInitial) _rooms.value = emptyMap()
            return
        }
        _rooms.update { current ->
            // An initial sync is the full picture: start from scratch, keeping only messages still being sent
            val updated = if (isInitial) {
                current.filterKeys { it in rooms.join }.mapValues { (roomId, room) -> RoomCache(roomId, pending = room.pending) }
                    .toMutableMap()
            } else {
                current.toMutableMap()
            }
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

    suspend fun sendText(roomId: String, body: String, replyToId: String? = null): EmptyResult<DataError.Remote> {
        val content = buildJsonObject {
            put("msgtype", "m.text")
            put("body", body)
            replyToId?.let { inReplyTo(it) }
        }
        return sendWithLocalEcho(roomId, PendingMessage(newTransactionId(), body, now(), replyToId = replyToId)) { session, txn ->
            api.sendEvent(session, roomId, "m.room.message", txn, content)
        }
    }

    /** Uploads the file to the media repository, then sends it as m.image / m.video / m.audio / m.file. */
    suspend fun sendAttachment(roomId: String, attachment: Attachment, caption: String?): EmptyResult<DataError.Remote> {
        val placeholder = PendingMessage(newTransactionId(), caption ?: attachment.fileName, now(), attachmentMimeType = attachment.mimeType)
        return sendWithLocalEcho(roomId, placeholder) { session, txn ->
            when (val upload = api.upload(session, attachment.bytes, attachment.mimeType, attachment.fileName)) {
                is Result.Failure -> upload
                is Result.Success -> api.sendEvent(session, roomId, "m.room.message", txn, buildJsonObject {
                    put("msgtype", msgTypeFor(attachment.mimeType))
                    // Caption goes in body with the file name separately (spec v1.10 media captions)
                    put("body", caption ?: attachment.fileName)
                    if (caption != null) put("filename", attachment.fileName)
                    put("url", upload.data.contentUri)
                    putJsonObject("info") {
                        put("mimetype", attachment.mimeType)
                        put("size", attachment.bytes.size)
                        attachment.width?.let { put("w", it) }
                        attachment.height?.let { put("h", it) }
                        attachment.voiceDurationMillis?.let { put("duration", it) }
                    }
                    // A voice message (MSC3245): bridges send it as the network's voice note
                    attachment.voiceDurationMillis?.let { duration ->
                        putJsonObject("org.matrix.msc3245.voice") {}
                        putJsonObject("org.matrix.msc1767.audio") {
                            put("duration", duration)
                            // MSC1767 waveforms run 0–1024
                            attachment.waveform?.let { samples -> putJsonArray("waveform") { samples.forEach { add(it * 4) } } }
                        }
                    }
                })
            }
        }
    }

    /** Adds an `m.annotation` reaction, or redacts the user's existing one with the same key. */
    suspend fun toggleReaction(roomId: String, messageId: String, key: String, ownReactionId: String?): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        return if (ownReactionId != null) {
            api.redact(session, roomId, ownReactionId, newTransactionId()).asEmptyResult()
        } else {
            api.sendEvent(session, roomId, "m.reaction", newTransactionId(), buildJsonObject {
                putJsonObject("m.relates_to") {
                    put("rel_type", "m.annotation")
                    put("event_id", messageId)
                    put("key", key)
                }
            }).asEmptyResult()
        }
    }

    suspend fun setTyping(roomId: String, isTyping: Boolean): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        return api.setTyping(session, roomId, isTyping, TYPING_TIMEOUT_MILLIS).asEmptyResult()
    }

    /** Sends a read receipt for the latest event, unless our receipt is already there. */
    suspend fun markAsRead(roomId: String): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        val room = _rooms.value[roomId] ?: return Result.Success(Unit)
        val lastEventId = room.timeline.lastOrNull { it.eventId != null && it.stateKey == null }?.eventId
            ?: return Result.Success(Unit)
        if (room.receipts[session.userId] == lastEventId && room.unreadCount == 0) return Result.Success(Unit)
        updateRoom(roomId) { it.copy(unreadCount = 0, receipts = it.receipts + (session.userId to lastEventId)) }
        return api.sendReadReceipt(session, roomId, lastEventId).asEmptyResult()
    }

    /** Authenticated media download (spec v1.11): `mxc://server/id` → HTTP URL + bearer header. */
    fun mediaRequest(mxcUrl: String, thumbnail: Boolean): MediaRequest? {
        val session = _session.value ?: return null
        val (server, mediaId) = mxcUrl.removePrefix("mxc://").split("/", limit = 2).takeIf { it.size == 2 } ?: return null
        val path = if (thumbnail) "thumbnail/$server/$mediaId?width=800&height=800&method=scale" else "download/$server/$mediaId"
        return MediaRequest(
            url = "${session.homeserverUrl}/_matrix/client/v1/media/$path",
            headers = mapOf("Authorization" to "Bearer ${session.accessToken}")
        )
    }

    suspend fun downloadMedia(mxcUrl: String): ByteArray? {
        val request = mediaRequest(mxcUrl, thumbnail = false) ?: return null
        return api.download(request.url, request.headers)
    }

    private suspend fun sendWithLocalEcho(
        roomId: String,
        pending: PendingMessage,
        send: suspend (AuthInfo, String) -> Result<SendEventResponseDto, DataError.Remote>
    ): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        updateRoom(roomId) { it.copy(pending = it.pending + pending) }
        return send(session, pending.transactionId)
            .onSuccess { response -> updatePending(roomId, pending.transactionId) { it.copy(eventId = response.eventId) } }
            .onFailure { updatePending(roomId, pending.transactionId) { it.copy(isFailed = true) } }
            .asEmptyResult()
    }

    private fun JsonObjectBuilder.inReplyTo(eventId: String) {
        putJsonObject("m.relates_to") {
            putJsonObject("m.in_reply_to") { put("event_id", eventId) }
        }
    }

    private fun msgTypeFor(mimeType: String) = when {
        mimeType.startsWith("image/") -> "m.image"
        mimeType.startsWith("video/") -> "m.video"
        mimeType.startsWith("audio/") -> "m.audio"
        else -> "m.file"
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()
    private fun newTransactionId() = "howyoudoin-${now()}-${Random.nextInt(0, Int.MAX_VALUE)}"

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
        const val REFRESH_TIMEOUT_MILLIS = 30_000L
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val TYPING_TIMEOUT_MILLIS = 10_000L
    }
}
