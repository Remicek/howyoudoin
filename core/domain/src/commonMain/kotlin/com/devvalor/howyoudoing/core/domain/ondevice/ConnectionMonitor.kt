package com.devvalor.howyoudoing.core.domain.ondevice

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A connected network that isn't working: shown in the inbox and as a notification until it's fixed (texts in chat
 * presentation).
 */
data class ConnectionProblem(
    val network: ChatNetwork,
    val kind: Kind,
    val detail: String? = null,
    /** A page on the network's website where the user has to act before it works again; Reconnect opens it. */
    val actionUrl: String? = null,
    /** The user tapped Reconnect and it still isn't working: they're shown how to disconnect and connect again. */
    val reconnectDidntHelp: Boolean = false,
    /** Which of the network's accounts (see [AccountRef]). */
    val slot: Int = 0
) {
    val account: AccountRef get() = AccountRef(network, slot)

    enum class Kind {
        /** Connecting failed (it keeps retrying in the background). */
        FAILED,

        /** The login ended without the user disconnecting (expired, revoked, unlinked on the phone). */
        LOGGED_OUT,

        /** Was connected, and still isn't back after reconnecting for a while and starting over: the user can help. */
        RECONNECTING,

        /** The network no longer accepts the saved login; it has to be logged in again. */
        LOGIN_EXPIRED,

        /** Connected, but the network won't send until the account's email or phone is verified (Discord). */
        UNVERIFIED
    }
}

/**
 * A network's website page the user has to act on (accept its terms, confirm it's them) before it works again, opened
 * signed in with the account's [cookies].
 */
data class AccountAction(
    val network: ChatNetwork,
    val url: String,
    val cookies: Map<String, String>,
    val slot: Int = 0
) {
    val account: AccountRef get() = AccountRef(network, slot)
}

/** Watches the networks connected on this phone and reports the ones that stopped working. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionMonitor(
    private val clients: List<OnDeviceMessenger>,
    appScope: CoroutineScope,
    // One thread for the bookkeeping below, in the order things happen
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
) {

    private val scope = CoroutineScope(appScope.coroutineContext + dispatcher)

    private val _problems = MutableStateFlow<List<ConnectionProblem>>(emptyList())
    val problems: StateFlow<List<ConnectionProblem>> = _problems.asStateFlow()

    private val _pendingAction = MutableStateFlow<AccountAction?>(null)

    /** The page the user opened with Reconnect to act on; shown until they're done (see [finishAction]). */
    val pendingAction: StateFlow<AccountAction?> = _pendingAction.asStateFlow()

    private val _linkedAccounts = MutableStateFlow<Set<AccountRef>>(emptySet())

    /** Accounts logged in on this phone, working or not (a logged-out or never-connected one isn't). */
    val linkedAccounts: StateFlow<Set<AccountRef>> = _linkedAccounts.asStateFlow()

    private val _linkedNetworks = MutableStateFlow<Set<ChatNetwork>>(emptySet())

    /** Networks with at least one account logged in on this phone. */
    val linkedNetworks: StateFlow<Set<ChatNetwork>> = _linkedNetworks.asStateFlow()

    /** Accounts the user is disconnecting right now: their logout is expected. */
    private val disconnecting = mutableSetOf<AccountRef>()
    private val reconnectTimers = mutableMapOf<AccountRef, Job>()

    /** Accounts the user asked to reconnect that haven't connected since. */
    private val userReconnected = mutableSetOf<AccountRef>()

    init {
        clients.forEach { client -> watch(client) }
    }

    /** Call before the user disconnects [account], so it isn't reported as logged out. */
    fun expectDisconnect(account: AccountRef) {
        scope.launch {
            disconnecting += account
            clear(account)
        }
    }

    /** The user saw a "logged out" problem and doesn't want to reconnect. */
    fun dismiss(account: AccountRef) {
        scope.launch { clear(account) }
    }

    /**
     * Starts over the networks that lost their connection and haven't got it back (reconnecting, or failed), e.g. as
     * soon as the phone is online again rather than at their next retry. Ones connecting for the first time are left
     * alone.
     */
    fun reconnectDropped() {
        scope.launch {
            val failed = _problems.value
                .filter { it.kind == ConnectionProblem.Kind.FAILED || it.kind == ConnectionProblem.Kind.RECONNECTING }
                .map { it.account }
            for (account in (reconnectTimers.keys + failed).toSet()) {
                val client = clients.firstOrNull { it.account == account } ?: continue
                scope.launch { client.reconnect() }
            }
        }
    }

    /**
     * The user asked to reconnect the networks that are stuck reconnecting or failed: each starts over. One that
     * waits for the user on its website opens that page instead (see [pendingAction]); it reconnects when they're done.
     * One that fails again, or isn't back within [USER_RECONNECT_WAIT_MILLIS], comes back marked
     * [ConnectionProblem.reconnectDidntHelp].
     */
    fun reconnect() {
        scope.launch {
            val stuck = _problems.value.filter {
                it.kind == ConnectionProblem.Kind.RECONNECTING || it.kind == ConnectionProblem.Kind.FAILED
            }
            for (problem in stuck) {
                val client = clients.firstOrNull { it.account == problem.account } ?: continue
                val actionUrl = problem.actionUrl
                when {
                    actionUrl != null -> if (_pendingAction.value == null) {
                        _pendingAction.value =
                            AccountAction(problem.network, actionUrl, client.sessionCookies(), problem.slot)
                    }

                    else -> {
                        // Gone while it tries; back (marked) if this attempt doesn't get it working either
                        userReconnected += problem.account
                        clear(problem.account)
                        reconnectTimers.remove(problem.account)?.cancel()
                        watchReconnect(client, askAfter = USER_RECONNECT_WAIT_MILLIS)
                        scope.launch { client.reconnect() }
                    }
                }
            }
        }
    }

    /**
     * The user closed the page from [pendingAction]. Done ([cookies] being what the website has now, which may have
     * renewed the login's): the network reconnects; cancelled: the problem stays.
     */
    fun finishAction(cookies: Map<String, String>?) {
        scope.launch {
            val action = _pendingAction.value ?: return@launch
            _pendingAction.value = null
            if (cookies == null) return@launch
            val client = clients.firstOrNull { it.account == action.account } ?: return@launch
            client.updateSessionCookies(cookies)
            clear(action.account)
            scope.launch { client.reconnect() }
        }
    }

    private fun watch(client: OnDeviceMessenger) {
        var previous: OnDeviceMessengerState? = null
        var wasConnected = false
        client.state.onEach { state ->
            val account = client.account
            if (state !is OnDeviceMessengerState.Connecting) reconnectTimers.remove(account)?.cancel()
            _linkedAccounts.update { if (state == OnDeviceMessengerState.NotLinked) it - account else it + account }
            _linkedNetworks.value = _linkedAccounts.value.mapTo(mutableSetOf()) { it.network }
            when (state) {
                is OnDeviceMessengerState.Connected -> {
                    wasConnected = true
                    disconnecting -= account
                    userReconnected -= account
                    if (state.needsVerification) {
                        report(problem(account, ConnectionProblem.Kind.UNVERIFIED))
                    } else {
                        clear(account)
                    }
                }

                is OnDeviceMessengerState.Failed -> report(
                    if (isExpiredLogin(state.message)) {
                        problem(account, ConnectionProblem.Kind.LOGIN_EXPIRED, state.message)
                    } else problem(account, ConnectionProblem.Kind.FAILED, state.message, state.actionUrl)
                )

                OnDeviceMessengerState.Connecting -> if (wasConnected && reconnectTimers[account]?.isActive != true) {
                    watchReconnect(client, startOverAfter = RECONNECT_GRACE_MILLIS)
                }

                OnDeviceMessengerState.NotLinked -> {
                    val wasLinked = previous != null && previous != OnDeviceMessengerState.NotLinked
                    if (wasLinked && wasConnected && account !in disconnecting) {
                        report(problem(account, ConnectionProblem.Kind.LOGGED_OUT))
                    } else {
                        // Disconnected on purpose, or a login that never worked (its screen showed the error)
                        clear(account)
                    }
                    disconnecting -= account
                    userReconnected -= account
                    wasConnected = false
                }
            }
            previous = state
        }.launchIn(scope)
    }

    /**
     * Brief reconnects are normal (network changes) and aren't worth telling. A long one first gets a fresh
     * connection ([startOverAfter]); if that doesn't bring it back either, the user is asked to help.
     */
    private fun watchReconnect(
        client: OnDeviceMessenger,
        startOverAfter: Long? = null,
        askAfter: Long = ASK_USER_AFTER_MILLIS
    ) {
        val account = client.account
        reconnectTimers[account] = scope.launch {
            if (startOverAfter != null) {
                delay(startOverAfter)
                // Its own job: a failure while connecting ends this one, and that mustn't cancel the connecting
                scope.launch { client.reconnect() }
            }
            delay(askAfter)
            report(problem(account, ConnectionProblem.Kind.RECONNECTING))
        }
    }

    private fun report(problem: ConnectionProblem) {
        val stillBroken = problem.kind == ConnectionProblem.Kind.FAILED ||
            problem.kind == ConnectionProblem.Kind.RECONNECTING
        val reported = if (stillBroken && problem.account in userReconnected) {
            problem.copy(reconnectDidntHelp = true)
        } else problem
        _problems.update { current -> current.filterNot { it.account == problem.account } + reported }
    }

    private fun problem(
        account: AccountRef,
        kind: ConnectionProblem.Kind,
        detail: String? = null,
        actionUrl: String? = null
    ) = ConnectionProblem(account.network, kind, detail, actionUrl, slot = account.slot)

    private fun clear(account: AccountRef) {
        _problems.update { current -> current.filterNot { it.account == account } }
    }

    private companion object {
        const val RECONNECT_GRACE_MILLIS = 3 * 60_000L

        /** How long a fresh connection gets before the user is asked. */
        const val ASK_USER_AFTER_MILLIS = 7 * 60_000L

        /** How long a reconnect the user asked for gets before it counts as not having helped (they're waiting). */
        const val USER_RECONNECT_WAIT_MILLIS = 60_000L

        /** How the native clients say a saved login stopped working. */
        val EXPIRED_LOGIN_HINTS = listOf(
            "access token is no longer valid", "session expired", "log in again", "invalid_auth", "not_authed",
            "token_revoked", "logged out", "unauthorized"
        )

        fun isExpiredLogin(message: String) = EXPIRED_LOGIN_HINTS.any { message.contains(it, ignoreCase = true) }
    }
}
