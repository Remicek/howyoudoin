package com.devvalor.howyoudoing.chat.data.matrix

import com.devvalor.howyoudoing.core.domain.util.AppLog
import com.devvalor.howyoudoing.chat.data.matrix.dto.InvitedRoomDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.SendEventResponseDto
import com.devvalor.howyoudoing.chat.domain.Attachment
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import com.devvalor.howyoudoing.chat.data.matrix.dto.SyncResponseDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.string
import com.devvalor.howyoudoing.chat.domain.MatrixEncryptionStatus
import com.devvalor.howyoudoing.chat.domain.SyncState
import com.devvalor.howyoudoing.core.domain.auth.AuthInfo
import com.devvalor.howyoudoing.core.domain.auth.SessionStorage
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.EmptyResult
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.domain.util.asEmptyResult
import com.devvalor.howyoudoing.core.domain.util.onFailure
import com.devvalor.howyoudoing.core.domain.util.onSuccess
import kotlinx.coroutines.CoroutineScope
import kotlin.concurrent.Volatile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.IO
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.devvalor.howyoudoing.chat.data.matrix.dto.MatrixEventDto
import com.devvalor.howyoudoing.chat.data.matrix.dto.TurnServerDto
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
 * The cache and next_batch token aren't persisted, so a cold start does a full initial sync.
 * Encrypted rooms are decrypted with [MatrixCrypto] where the platform has it (Android): every sync goes through
 * it first (room keys arrive as to-device events), then encrypted timeline events are swapped for their decrypted
 * form; events whose keys come later are retried after each sync.
 */
@OptIn(ExperimentalTime::class)
class MatrixSyncEngine(
    private val api: MatrixApi,
    private val sessionStorage: SessionStorage,
    private val applicationScope: CoroutineScope,
    /**
     * Also accept direct-chat invites from people (the Matrix account connected as a network); the app's own
     * server session only takes bridge invites.
     */
    private val acceptDirectInvites: Boolean = false,
    private val cryptoFactory: MatrixCryptoFactory? = null,
    private val mediaCache: MatrixMediaCache? = null
) : MatrixCallTransport {

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile
    private var crypto: MatrixCrypto? = null
    private val _encryption = MutableStateFlow(MatrixEncryptionStatus.UNAVAILABLE)
    val encryption: StateFlow<MatrixEncryptionStatus> = _encryption.asStateFlow()

    // Encrypted media seen in decrypted events: mxc:// URL → its `file` object (key, iv, hashes) as JSON
    private val encryptedFiles = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _rooms = MutableStateFlow<Map<String, RoomCache>>(emptyMap())
    val rooms: StateFlow<Map<String, RoomCache>> = _rooms.asStateFlow()

    // A Matrix room's encryption can never be turned off (the spec forbids it), so once we have ever seen a room
    // encrypted we refuse to send plaintext to it, even if a malicious or compromised homeserver later drops the
    // m.room.encryption state from /sync to force a silent downgrade.
    private val everEncrypted = MutableStateFlow<Set<String>>(emptySet())

    private fun isRoomEncrypted(roomId: String): Boolean {
        val current = _rooms.value[roomId]?.stateEvent(ENCRYPTION_STATE) != null
        if (current) everEncrypted.update { it + roomId }
        return current || roomId in everEncrypted.value
    }

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _session = MutableStateFlow<AuthInfo?>(null)
    val session: StateFlow<AuthInfo?> = _session.asStateFlow()

    override val userId: String? get() = _session.value?.userId

    private val attemptedInvites = mutableSetOf<String>()

    // Invitations waiting for the user (the ones not accepted automatically)
    private val _invites = MutableStateFlow<Map<String, MatrixInvite>>(emptyMap())
    val invites: StateFlow<Map<String, MatrixInvite>> = _invites.asStateFlow()

    // VoIP signalling (m.call.*) as it arrives; see MatrixCallManager
    private val _callEvents = MutableSharedFlow<MatrixCallEvent>(extraBufferCapacity = 256)
    override val callEvents: SharedFlow<MatrixCallEvent> = _callEvents.asSharedFlow()

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
                    _invites.value = emptyMap()
                    attemptedInvites.clear()
                    openCrypto(session)
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

    override fun ensureStarted() {
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
        // Opening the keys failed before (offline?): try again with each fresh start
        if (crypto == null && _encryption.value != MatrixEncryptionStatus.NEEDS_SIGN_IN) openCrypto(session)
        var since: String? = null
        var backoffMillis = INITIAL_BACKOFF_MILLIS
        while (currentCoroutineContext().isActive) {
            if (since == null) _syncState.value = SyncState.CONNECTING
            val raw = api.syncRaw(
                session = session,
                since = since,
                timeoutMillis = if (since == null) 0L else LONG_POLL_MILLIS
            )
            val result = when (raw) {
                is Result.Failure -> raw
                is Result.Success -> runCatching { json.decodeFromJsonElement(SyncResponseDto.serializer(), raw.data) }
                    .fold({ Result.Success(it) }, { Result.Failure(DataError.Remote.SERIALIZATION) })
            }
            when (result) {
                is Result.Success -> {
                    val sync = withCrypto(result.data) { crypto ->
                        crypto.processSync((raw as Result.Success).data.toString(), since)
                        decryptSync(crypto, result.data)
                    } ?: result.data
                    applySync(session, sync, isInitial = since == null)
                    retryUndecrypted()
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
                current.filterKeys { it in rooms.join }
                    .mapValues { (roomId, room) -> RoomCache(roomId, pending = room.pending) }
                    .toMutableMap()
            } else {
                current.toMutableMap()
            }
            rooms.leave.keys.forEach(updated::remove)
            rooms.join.forEach { (roomId, room) -> emitCallEvents(roomId, room.timeline?.events.orEmpty(), isInitial) }
            rooms.join.forEach { (roomId, room) ->
                updated[roomId] = (updated[roomId] ?: RoomCache(roomId)).merge(room)
            }
            updated
        }
        // Remember which rooms are encrypted, so a later /sync that omits m.room.encryption can't downgrade them.
        val encryptedNow = _rooms.value.filterValues { it.stateEvent(ENCRYPTION_STATE) != null }.keys
        if (encryptedNow.isNotEmpty()) everEncrypted.update { existing -> existing + encryptedNow }
        _invites.update { current ->
            val kept = if (isInitial) emptyMap() else current - rooms.join.keys - rooms.leave.keys
            kept + rooms.invite.mapValues { (roomId, invite) -> invite.toInvite(roomId, session.userId) }
        }
        autoJoinInvites(session, rooms.invite)
    }

    /**
     * Passes on VoIP events. An initial sync also carries old calls, so only recent events count there (a call
     * still ringing, and whatever ended it).
     */
    private fun emitCallEvents(roomId: String, events: List<MatrixEventDto>, isInitial: Boolean) {
        events.filter { it.type.startsWith("m.call.") && it.sender != null }.forEach { event ->
            val age = event.unsigned?.string("age")?.toLongOrNull() ?: 0L
            if (isInitial && age > CALL_EVENT_MAX_AGE_MILLIS) return@forEach
            _callEvents.tryEmit(MatrixCallEvent(roomId, event.type, event.sender!!, event.content, age))
        }
    }

    /** Sends a VoIP event (m.call.invite, m.call.answer, …) to a room. */
    override suspend fun sendCallEvent(
        roomId: String,
        type: String,
        content: JsonObject
    ): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        return sendRoomEvent(session, roomId, type, newTransactionId(), content).asEmptyResult()
    }

    /** The homeserver's TURN servers for calls (none if it doesn't offer any). */
    override suspend fun turnServer(): TurnServerDto? {
        val session = _session.value ?: return null
        return (api.turnServer(session) as? Result.Success)?.data
    }

    /**
     * Bridges create a portal room per chat and invite us. Without double puppeting those invites must be accepted by
     * the client, so invites from a bridge ghost or bot on our own homeserver are accepted (see [shouldAutoJoin]);
     * with [acceptDirectInvites], also people's direct-chat invites (group invites still aren't accepted).
     */
    private suspend fun autoJoinInvites(session: AuthInfo, invites: Map<String, InvitedRoomDto>) {
        invites.forEach { (roomId, invite) ->
            if (!attemptedInvites.add(roomId)) return@forEach
            val ownMembership = invite.inviteState?.events
                ?.firstOrNull { it.type == "m.room.member" && it.stateKey == session.userId }
                ?.takeIf { it.content.string("membership") == "invite" }
                ?: return@forEach
            if (shouldAutoJoin(ownMembership.sender, session.userId, isDirectInvite(ownMembership.content),
                    acceptDirectInvites)
            ) {
                api.joinRoom(session, roomId).onSuccess { _invites.update { it - roomId } }
            }
        }
    }

    suspend fun acceptInvite(roomId: String): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        return api.joinRoom(session, roomId)
            .onSuccess { _invites.update { it - roomId } }
            .asEmptyResult()
    }

    suspend fun declineInvite(roomId: String): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        return api.leaveRoom(session, roomId)
            .onSuccess { _invites.update { it - roomId } }
            .asEmptyResult()
    }

    // --- direct chats (MatrixDirectChats) ---

    /** This device can encrypt, so new rooms can be end-to-end encrypted. */
    val canEncrypt: Boolean get() = crypto != null

    /** A room just created encrypted: never send plain text to it, even before a sync shows its encryption. */
    fun rememberEncrypted(roomId: String) = everEncrypted.update { it + roomId }

    private fun InvitedRoomDto.toInvite(roomId: String, ownUserId: String): MatrixInvite {
        val events = inviteState?.events.orEmpty()
        val own = events.firstOrNull { it.type == "m.room.member" && it.stateKey == ownUserId }
        val inviter = own?.sender.orEmpty()
        val inviterName = events.firstOrNull { it.type == "m.room.member" && it.stateKey == inviter }
            ?.content?.string("displayname")
        return MatrixInvite(
            roomId = roomId,
            roomName = events.firstOrNull { it.type == "m.room.name" }?.content?.string("name"),
            inviterId = inviter,
            inviterName = inviterName ?: inviter,
            timestamp = own?.originServerTs ?: now(),
            isDirect = isDirectInvite(own?.content)
        )
    }

    // --- encryption ---

    private suspend fun openCrypto(session: AuthInfo?) {
        crypto?.let { old -> withContext(Dispatchers.IO) { old.close() } }
        crypto = null
        _encryption.value = MatrixEncryptionStatus.UNAVAILABLE
        val opened = try {
            session?.let { cryptoFactory?.open(it) }
        } catch (e: MatrixKeysLostException) {
            AppLog.d { "MatrixCrypto: keys lost, sign-in needed (${e.message})" }
            _encryption.value = MatrixEncryptionStatus.NEEDS_SIGN_IN
            null
        } ?: return
        crypto = opened
        _encryption.value = encryptionStatus(opened)
    }

    private suspend fun encryptionStatus(crypto: MatrixCrypto) = withContext(Dispatchers.IO) {
        val verified = runCatching { crypto.isVerified() }.getOrDefault(false)
        if (verified) MatrixEncryptionStatus.VERIFIED else MatrixEncryptionStatus.UNVERIFIED
    }

    /** Runs [block] with the crypto on a background thread; null when there's no crypto or it failed. */
    private suspend fun <T> withCrypto(subject: Any, block: (MatrixCrypto) -> T): T? {
        val crypto = crypto ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { block(crypto) }
                .onFailure { AppLog.d { "MatrixCrypto: ${it.message} (${subject::class.simpleName})" } }
                .getOrNull()
        }
    }

    private fun decryptSync(crypto: MatrixCrypto, sync: SyncResponseDto): SyncResponseDto {
        val rooms = sync.rooms ?: return sync
        return sync.copy(rooms = rooms.copy(join = rooms.join.mapValues { (roomId, room) ->
            val timeline = room.timeline ?: return@mapValues room
            room.copy(timeline = timeline.copy(events = decryptEvents(crypto, roomId, timeline.events)))
        }))
    }

    private fun decryptEvents(
        crypto: MatrixCrypto,
        roomId: String,
        events: List<MatrixEventDto>
    ): List<MatrixEventDto> =
        events.map { event -> if (event.type == ENCRYPTED) decryptEvent(crypto, roomId, event) ?: event else event }

    private fun decryptEvent(crypto: MatrixCrypto, roomId: String, event: MatrixEventDto): MatrixEventDto? {
        val result = crypto.decrypt(roomId, json.encodeToString(MatrixEventDto.serializer(), event))
        val decrypted = (result as? DecryptResult.Decrypted)?.let { json.parseToJsonElement(it.json).jsonObject }
        val type = decrypted?.get("type")?.jsonPrimitive?.contentOrNull
        val content = decrypted?.get("content") as? JsonObject
        if (type == null || content == null) return null
        rememberEncryptedFiles(content)
        return event.copy(type = type, content = content)
    }

    private fun rememberEncryptedFiles(content: JsonObject) {
        val files = listOfNotNull(
            content["file"] as? JsonObject,
            (content["info"] as? JsonObject)?.get("thumbnail_file") as? JsonObject
        )
        val byUrl =
            files.mapNotNull { file -> file["url"]?.jsonPrimitive?.contentOrNull?.let { it to file.toString() } }
        if (byUrl.isNotEmpty()) encryptedFiles.update { it + byUrl }
    }

    /** Events that couldn't be decrypted yet: their keys may have come with this sync (or the key backup). */
    private suspend fun retryUndecrypted() {
        val waiting = _rooms.value.mapValues { (_, room) ->
            room.timeline.filter { it.type == ENCRYPTED }.takeLast(RETRY_DECRYPT_LIMIT)
        }.filterValues { it.isNotEmpty() }
        if (waiting.isEmpty()) return
        val decrypted = withCrypto(waiting) { crypto ->
            waiting.mapValues { (roomId, events) ->
                events.mapNotNull { event -> decryptEvent(crypto, roomId, event)?.let { event.eventId to it } }.toMap()
            }
        } ?: return
        decrypted.filterValues { it.isNotEmpty() }.forEach { (roomId, byId) ->
            updateRoom(roomId) { room -> room.copy(timeline = room.timeline.map { byId[it.eventId] ?: it }) }
        }
    }

    /** Sends a room event, encrypted when the room is. */
    private suspend fun sendRoomEvent(
        session: AuthInfo,
        roomId: String,
        type: String,
        transactionId: String,
        content: JsonObject
    ): Result<SendEventResponseDto, DataError.Remote> {
        val isEncrypted = isRoomEncrypted(roomId)
        val roomCrypto = crypto
        return when {
            !isEncrypted -> api.sendEvent(session, roomId, type, transactionId, content)
            // Never send in plain text to an encrypted room
            roomCrypto == null -> Result.Failure(DataError.Remote.FORBIDDEN)
            else -> encrypt(roomCrypto, roomId, type, content)
                ?.let { api.sendEvent(session, roomId, ENCRYPTED, transactionId, it) }
                ?: Result.Failure(DataError.Remote.UNKNOWN)
        }
    }

    private suspend fun encrypt(crypto: MatrixCrypto, roomId: String, type: String, content: JsonObject) =
        withContext(Dispatchers.IO) {
            runCatching { json.parseToJsonElement(crypto.encrypt(roomId, type, content.toString())).jsonObject }
                .onFailure { AppLog.d { "MatrixCrypto: encrypt failed: ${it.message}" } }
                .getOrNull()
        }

    /** Verifies this device with the account's recovery key; returns how many room keys came from the key backup. */
    suspend fun verifyWithRecoveryKey(recoveryKey: String): kotlin.Result<Int> {
        val crypto = crypto ?: return kotlin.Result.failure(IllegalStateException("Encryption isn't available"))
        val result = withContext(Dispatchers.IO) { runCatching { crypto.verifyWithRecoveryKey(recoveryKey.trim()) } }
        _encryption.value = encryptionStatus(crypto)
        if (result.isSuccess) retryUndecrypted()
        return result
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
            .onSuccess { response ->
                val decrypted = withCrypto(response) { crypto ->
                    response.copy(
                        chunk = decryptEvents(
                            crypto,
                            roomId,
                            response.chunk
                        )
                    )
                }
                updateRoom(roomId) { it.prependHistory(decrypted ?: response) }
            }
            .asEmptyResult()
    }

    suspend fun sendText(roomId: String, body: String, replyToId: String? = null): EmptyResult<DataError.Remote> {
        val content = buildJsonObject {
            put("msgtype", "m.text")
            put("body", body)
            replyToId?.let { inReplyTo(it) }
        }
        return sendWithLocalEcho(
            roomId,
            PendingMessage(newTransactionId(), body, now(), replyToId = replyToId)
        ) { session, txn ->
            sendRoomEvent(session, roomId, "m.room.message", txn, content)
        }
    }

    /** Sends a text message that failed again, as a new message. */
    suspend fun retryFailed(roomId: String, transactionId: String): EmptyResult<DataError.Remote> {
        val failed = _rooms.value[roomId]?.pending
            ?.firstOrNull { it.transactionId == transactionId && it.isFailed && it.attachmentMimeType == null }
            ?: return Result.Failure(DataError.Remote.BAD_REQUEST)
        discardFailed(roomId, transactionId)
        return sendText(roomId, failed.body, failed.replyToId)
    }

    fun discardFailed(roomId: String, transactionId: String) = updateRoom(roomId) { room ->
        room.copy(pending = room.pending.filterNot { it.transactionId == transactionId && it.isFailed })
    }

    /** Uploads the file to the media repository, then sends it as m.image / m.video / m.audio / m.file. */
    suspend fun sendAttachment(
        roomId: String,
        attachment: Attachment,
        caption: String?
    ): EmptyResult<DataError.Remote> {
        val placeholder = PendingMessage(
            newTransactionId(),
            caption ?: attachment.fileName,
            now(),
            attachmentMimeType = attachment.mimeType
        )
        val isEncrypted = isRoomEncrypted(roomId)
        return sendWithLocalEcho(roomId, placeholder) { session, txn ->
            // In an encrypted room only the ciphertext is uploaded; the key travels in the (encrypted) event
            val encrypted = if (isEncrypted) {
                withCrypto(attachment.fileName) { it.encryptAttachment(attachment.bytes) }
                    ?: return@sendWithLocalEcho Result.Failure(DataError.Remote.UNKNOWN)
            } else null
            val upload = if (encrypted != null) {
                api.upload(session, encrypted.ciphertext, "application/octet-stream", attachment.fileName)
            } else {
                api.upload(session, attachment.bytes, attachment.mimeType, attachment.fileName)
            }
            when (upload) {
                is Result.Failure -> upload
                is Result.Success -> sendRoomEvent(
                    session,
                    roomId,
                    "m.room.message",
                    txn,
                    attachmentContent(attachment, caption, upload.data.contentUri, encrypted)
                )
            }
        }
    }

    private suspend fun attachmentContent(
        attachment: Attachment,
        caption: String?,
        mxcUrl: String,
        encrypted: EncryptedAttachment?
    ): JsonObject = buildJsonObject {
        put("msgtype", msgTypeFor(attachment.mimeType))
        // Caption goes in body with the file name separately (spec v1.10 media captions)
        put("body", caption ?: attachment.fileName)
        if (caption != null) put("filename", attachment.fileName)
        if (encrypted != null) {
            val keys = json.parseToJsonElement(encrypted.fileJson).jsonObject
            val file = JsonObject(keys + ("url" to JsonPrimitive(mxcUrl)))
            put("file", file)
            // Our own copy shows without downloading it again
            encryptedFiles.update { it + (mxcUrl to file.toString()) }
            withContext(Dispatchers.IO) { mediaCache?.write(mxcUrl, attachment.bytes) }
        } else {
            put("url", mxcUrl)
        }
        putJsonObject("info") {
            put("mimetype", attachment.mimeType)
            put("size", attachment.bytes.size)
            attachment.width?.let { put("w", it) }
            attachment.height?.let { put("h", it) }
            (attachment.voice?.durationMillis ?: attachment.durationMillis)?.let { put("duration", it) }
        }
        // A voice message (MSC3245): bridges send it as the network's voice note
        attachment.voice?.let { voice ->
            putJsonObject("org.matrix.msc3245.voice") {}
            putJsonObject("org.matrix.msc1767.audio") {
                put("duration", voice.durationMillis)
                // MSC1767 waveforms run 0–1024
                voice.waveform?.let { samples ->
                    putJsonArray("waveform") { samples.forEach { add(it * MSC1767_WAVEFORM_SCALE) } }
                }
            }
        }
    }

    /** Sends an `m.replace` edit of one of our messages. */
    suspend fun editMessage(roomId: String, eventId: String, body: String): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        return sendRoomEvent(session, roomId, "m.room.message", newTransactionId(), buildJsonObject {
            put("msgtype", "m.text")
            put("body", "* $body")
            putJsonObject("m.new_content") {
                put("msgtype", "m.text")
                put("body", body)
            }
            putJsonObject("m.relates_to") {
                put("rel_type", "m.replace")
                put("event_id", eventId)
            }
        }).asEmptyResult()
    }

    /** Redacts one of our messages; bridges delete it on the network. */
    suspend fun deleteMessage(roomId: String, eventId: String): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        return api.redact(session, roomId, eventId, newTransactionId()).asEmptyResult()
    }

    /** Adds an `m.annotation` reaction, or redacts the user's existing one with the same key. */
    suspend fun toggleReaction(
        roomId: String,
        messageId: String,
        key: String,
        ownReactionId: String?
    ): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        return if (ownReactionId != null) {
            api.redact(session, roomId, ownReactionId, newTransactionId()).asEmptyResult()
        } else {
            sendRoomEvent(session, roomId, "m.reaction", newTransactionId(), buildJsonObject {
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
        val (server, mediaId) = mxcUrl.removePrefix("mxc://").split("/", limit = 2).takeIf { it.size == 2 }
            ?: return null
        // Encode the server/id from the (event-supplied) mxc URL so they can't inject extra path or query parts.
        val safeServer = server.encodeURLPathPart()
        val safeMediaId = mediaId.encodeURLPathPart()
        val path =
            if (thumbnail) {
                "thumbnail/$safeServer/$safeMediaId?width=800&height=800&method=scale"
            } else {
                "download/$safeServer/$safeMediaId"
            }
        return MediaRequest(
            url = "${session.homeserverUrl}/_matrix/client/v1/media/$path",
            headers = mapOf("Authorization" to "Bearer ${session.accessToken}")
        )
    }

    /** Like [mediaRequest], but media of encrypted rooms is downloaded and decrypted into a local file first. */
    suspend fun resolveMedia(mxcUrl: String, thumbnail: Boolean): MediaRequest? {
        if (mxcUrl !in encryptedFiles.value) return mediaRequest(mxcUrl, thumbnail)
        val path = mediaCache?.path(mxcUrl) ?: decryptedMedia(mxcUrl)?.let { bytes ->
            withContext(Dispatchers.IO) { mediaCache?.write(mxcUrl, bytes) }
        } ?: return null
        return MediaRequest("file://$path", emptyMap())
    }

    suspend fun downloadMedia(mxcUrl: String): ByteArray? {
        if (mxcUrl in encryptedFiles.value) {
            return withContext(Dispatchers.IO) { mediaCache?.read(mxcUrl) } ?: decryptedMedia(mxcUrl)
        }
        val request = mediaRequest(mxcUrl, thumbnail = false) ?: return null
        return api.download(request.url, request.headers)
    }

    /**
     * Downloads an encrypted room's file (always in full: the server can't make thumbnails of ciphertext) and decrypts
     * it.
     */
    private suspend fun decryptedMedia(mxcUrl: String): ByteArray? {
        val fileJson = encryptedFiles.value[mxcUrl] ?: return null
        val request = mediaRequest(mxcUrl, thumbnail = false) ?: return null
        val ciphertext = api.download(request.url, request.headers) ?: return null
        return withCrypto(mxcUrl) { it.decryptAttachment(ciphertext, fileJson) }
    }

    private suspend fun sendWithLocalEcho(
        roomId: String,
        pending: PendingMessage,
        send: suspend (AuthInfo, String) -> Result<SendEventResponseDto, DataError.Remote>
    ): EmptyResult<DataError.Remote> {
        val session = _session.value ?: return Result.Failure(DataError.Remote.UNAUTHORIZED)
        updateRoom(roomId) { it.copy(pending = it.pending + pending) }
        return send(session, pending.transactionId)
            .onSuccess { response ->
                updatePending(
                    roomId,
                    pending.transactionId
                ) { it.copy(eventId = response.eventId) }
            }
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
        private const val ENCRYPTED = "m.room.encrypted"
        private const val ENCRYPTION_STATE = "m.room.encryption"
        private const val RETRY_DECRYPT_LIMIT = 50
        const val LONG_POLL_MILLIS = 30_000L
        const val REFRESH_TIMEOUT_MILLIS = 30_000L
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        // A server that stays unreachable (e.g. a development server that's off) is tried less and less often, up to
        // every five minutes; pulling to refresh tries again at once
        const val MAX_BACKOFF_MILLIS = 300_000L
        const val TYPING_TIMEOUT_MILLIS = 10_000L
        const val CALL_EVENT_MAX_AGE_MILLIS = 60_000L
    }
}

/** A VoIP signalling event from a room; [ageMillis] is how long ago it was sent. */
data class MatrixCallEvent(
    val roomId: String,
    val type: String,
    val sender: String,
    val content: JsonObject,
    val ageMillis: Long
)

/** What calls need from a Matrix session: its VoIP events, sending them, and TURN servers. */
interface MatrixCallTransport {
    val userId: String?
    val callEvents: SharedFlow<MatrixCallEvent>
    fun ensureStarted()
    suspend fun sendCallEvent(roomId: String, type: String, content: JsonObject): EmptyResult<DataError.Remote>
    suspend fun turnServer(): TurnServerDto?
}

/** An invitation to a room that the user hasn't answered yet. */
data class MatrixInvite(
    val roomId: String,
    val roomName: String?,
    val inviterId: String,
    val inviterName: String,
    val timestamp: Long,
    val isDirect: Boolean
)

/** The app's waveforms run 0–255, MSC1767's 0–1024. */
private const val MSC1767_WAVEFORM_SCALE = 4
