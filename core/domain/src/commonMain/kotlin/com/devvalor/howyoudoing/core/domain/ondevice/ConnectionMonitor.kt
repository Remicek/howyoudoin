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

/** A connected network that isn't working: shown in the inbox and as a notification until it's fixed. */
data class ConnectionProblem(val network: ChatNetwork, val kind: Kind, val detail: String? = null) {
    enum class Kind {
        /** Connecting failed (it keeps retrying in the background). */
        FAILED,
        /** The login ended without the user disconnecting (expired, revoked, unlinked on the phone). */
        LOGGED_OUT,
        /** Was connected, and has been trying to reconnect for a while. */
        RECONNECTING,
        /** The network no longer accepts the saved login; it has to be logged in again. */
        LOGIN_EXPIRED
    }

    val title: String
        get() = when (kind) {
            Kind.FAILED -> "${network.displayName} isn't connected"
            Kind.LOGGED_OUT -> "${network.displayName} was logged out"
            Kind.RECONNECTING -> "${network.displayName} is reconnecting"
            Kind.LOGIN_EXPIRED -> "${network.displayName} login expired"
        }

    val text: String
        get() = when (kind) {
            Kind.FAILED -> detail?.takeIf { it.isNotBlank() } ?: "Couldn't connect. Retrying in the background."
            Kind.LOGGED_OUT -> "Connect it again in Networks to keep getting its messages."
            Kind.RECONNECTING -> "New messages may be delayed until it's back."
            Kind.LOGIN_EXPIRED -> "Disconnect it in Networks and log in again."
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
                    if (isExpiredLogin(state.message)) ConnectionProblem(network, ConnectionProblem.Kind.LOGIN_EXPIRED, state.message)
                    else ConnectionProblem(network, ConnectionProblem.Kind.FAILED, state.message)
                )
                OnDeviceMessengerState.Connecting -> if (wasConnected && reconnectTimers[network]?.isActive != true) {
                    // Brief reconnects are normal (network changes); only a long one is worth telling
                    reconnectTimers[network] = scope.launch {
                        delay(RECONNECT_GRACE_MILLIS)
                        report(ConnectionProblem(network, ConnectionProblem.Kind.RECONNECTING))
                    }
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

    private fun report(problem: ConnectionProblem) {
        _problems.update { current -> current.filterNot { it.network == problem.network } + problem }
    }

    private fun clear(network: ChatNetwork) {
        _problems.update { current -> current.filterNot { it.network == network } }
    }

    private companion object {
        const val RECONNECT_GRACE_MILLIS = 3 * 60_000L

        /** How the native clients say a saved login stopped working. */
        val EXPIRED_LOGIN_HINTS = listOf(
            "access token is no longer valid", "session expired", "log in again", "invalid_auth", "not_authed",
            "token_revoked", "logged out", "unauthorized"
        )

        fun isExpiredLogin(message: String) = EXPIRED_LOGIN_HINTS.any { message.contains(it, ignoreCase = true) }
    }
}
