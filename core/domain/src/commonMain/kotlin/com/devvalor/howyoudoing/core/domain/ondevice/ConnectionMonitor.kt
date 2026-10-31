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
data class ConnectionProblem(val network: ChatNetwork, val kind: Kind, val detail: String? = null) {
    enum class Kind {
        /** Connecting failed (it keeps retrying in the background). */
        FAILED,

        /** The login ended without the user disconnecting (expired, revoked, unlinked on the phone). */
        LOGGED_OUT,

        /** Was connected, and still isn't back after reconnecting for a while and starting over: the user can help. */
        RECONNECTING,

        /** The network no longer accepts the saved login; it has to be logged in again. */
        LOGIN_EXPIRED
    }
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

    /** Networks the user is disconnecting right now: their logout is expected. */
    private val disconnecting = mutableSetOf<ChatNetwork>()
    private val reconnectTimers = mutableMapOf<ChatNetwork, Job>()

    init {
        clients.forEach { client -> watch(client) }
    }

    /** Call before the user disconnects [network], so it isn't reported as logged out. */
    fun expectDisconnect(network: ChatNetwork) {
        scope.launch {
            disconnecting += network
            clear(network)
        }
    }

    /** The user saw a "logged out" problem and doesn't want to reconnect. */
    fun dismiss(network: ChatNetwork) {
        scope.launch { clear(network) }
    }

    /** The user asked to reconnect the networks that are stuck reconnecting or failed: each starts over. */
    fun reconnect() {
        scope.launch {
            val stuck = _problems.value.filter {
                it.kind == ConnectionProblem.Kind.RECONNECTING || it.kind == ConnectionProblem.Kind.FAILED
            }
            for (problem in stuck) {
                val client = clients.firstOrNull { it.network == problem.network } ?: continue
                if (problem.kind == ConnectionProblem.Kind.RECONNECTING) {
                    // Gone while it tries; asks again if this attempt doesn't get it back either
                    clear(problem.network)
                    reconnectTimers.remove(problem.network)?.cancel()
                    watchReconnect(client)
                }
                scope.launch { client.reconnect() }
            }
        }
    }

    private fun watch(client: OnDeviceMessenger) {
        var previous: OnDeviceMessengerState? = null
        var wasConnected = false
        client.state.onEach { state ->
            val network = client.network
            if (state !is OnDeviceMessengerState.Connecting) reconnectTimers.remove(network)?.cancel()
            when (state) {
                is OnDeviceMessengerState.Connected -> {
                    wasConnected = true
                    disconnecting -= network
                    clear(network)
                }

                is OnDeviceMessengerState.Failed -> report(
                    if (isExpiredLogin(state.message)) ConnectionProblem(
                        network,
                        ConnectionProblem.Kind.LOGIN_EXPIRED,
                        state.message
                    )
                    else ConnectionProblem(network, ConnectionProblem.Kind.FAILED, state.message)
                )

                OnDeviceMessengerState.Connecting -> if (wasConnected && reconnectTimers[network]?.isActive != true) {
                    watchReconnect(client, startOverAfter = RECONNECT_GRACE_MILLIS)
                }

                OnDeviceMessengerState.NotLinked -> {
                    val wasLinked = previous != null && previous != OnDeviceMessengerState.NotLinked
                    if (wasLinked && wasConnected && network !in disconnecting) {
                        report(ConnectionProblem(network, ConnectionProblem.Kind.LOGGED_OUT))
                    } else {
                        // Disconnected on purpose, or a login that never worked (its screen showed the error)
                        clear(network)
                    }
                    disconnecting -= network
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
    private fun watchReconnect(client: OnDeviceMessenger, startOverAfter: Long? = null) {
        val network = client.network
        reconnectTimers[network] = scope.launch {
            if (startOverAfter != null) {
                delay(startOverAfter)
                // Its own job: a failure while connecting ends this one, and that mustn't cancel the connecting
                scope.launch { client.reconnect() }
            }
            delay(ASK_USER_AFTER_MILLIS)
            report(ConnectionProblem(network, ConnectionProblem.Kind.RECONNECTING))
        }
    }

    private fun report(problem: ConnectionProblem) {
        _problems.update { current -> current.filterNot { it.network == problem.network } + problem }
    }

    private fun clear(network: ChatNetwork) {
        _problems.update { current -> current.filterNot { it.network == network } }
    }

    private companion object {
        const val RECONNECT_GRACE_MILLIS = 3 * 60_000L

        /** How long a fresh connection gets before the user is asked. */
        const val ASK_USER_AFTER_MILLIS = 7 * 60_000L

        /** How the native clients say a saved login stopped working. */
        val EXPIRED_LOGIN_HINTS = listOf(
            "access token is no longer valid", "session expired", "log in again", "invalid_auth", "not_authed",
            "token_revoked", "logged out", "unauthorized"
        )

        fun isExpiredLogin(message: String) = EXPIRED_LOGIN_HINTS.any { message.contains(it, ignoreCase = true) }
    }
}
