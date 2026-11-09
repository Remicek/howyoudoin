package com.devvalor.howyoudoing.core.domain.ondevice

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionMonitorTest {

    private class FakeClient(override val network: ChatNetwork) : OnDeviceMessenger by UnsupportedOnDeviceMessenger {
        override val state = MutableStateFlow<OnDeviceMessengerState>(OnDeviceMessengerState.NotLinked)
        var reconnects = 0
        override suspend fun reconnect() {
            reconnects++
        }
    }

    private val whatsApp = FakeClient(ChatNetwork.WHATSAPP)
    private val slack = FakeClient(ChatNetwork.SLACK)

    private fun TestScope.monitor() =
        ConnectionMonitor(
            listOf(whatsApp, slack),
            backgroundScope,
            StandardTestDispatcher(testScheduler)
        ).also { runCurrent() }

    private fun TestScope.set(client: FakeClient, state: OnDeviceMessengerState) {
        client.state.value = state
        runCurrent()
    }

    @Test
    fun failedConnectionIsReportedUntilConnected() = runTest {
        val monitor = monitor()
        set(whatsApp, OnDeviceMessengerState.Connecting)
        set(whatsApp, OnDeviceMessengerState.Failed("No network"))
        val problem = monitor.problems.value.single()
        assertEquals(ChatNetwork.WHATSAPP, problem.network)
        assertEquals(ConnectionProblem.Kind.FAILED, problem.kind)
        assertEquals("No network", problem.detail)

        set(whatsApp, OnDeviceMessengerState.Connected("Remik"))
        assertTrue(monitor.problems.value.isEmpty())
    }

    @Test
    fun expiredLoginSaysToLogInAgain() = runTest {
        val monitor = monitor()
        set(
            whatsApp,
            OnDeviceMessengerState.Failed(
                "Failed to load inbox: access token is no longer valid: user id in initial data is zero"
            )
        )
        val problem = monitor.problems.value.single()
        assertEquals(ConnectionProblem.Kind.LOGIN_EXPIRED, problem.kind)
        assertEquals(ChatNetwork.WHATSAPP, problem.network)
    }

    @Test
    fun loggedOutWithoutTheUserIsReported() = runTest {
        val monitor = monitor()
        set(slack, OnDeviceMessengerState.Connected("Team"))
        set(slack, OnDeviceMessengerState.NotLinked)
        assertEquals(ConnectionProblem.Kind.LOGGED_OUT, monitor.problems.value.single().kind)

        monitor.dismiss(ChatNetwork.SLACK)
        runCurrent()
        assertTrue(monitor.problems.value.isEmpty())
    }

    @Test
    fun disconnectingOnPurposeIsNotAProblem() = runTest {
        val monitor = monitor()
        set(slack, OnDeviceMessengerState.Connected("Team"))
        monitor.expectDisconnect(ChatNetwork.SLACK)
        set(slack, OnDeviceMessengerState.NotLinked)
        assertTrue(monitor.problems.value.isEmpty())
    }

    @Test
    fun aFailedFirstLoginGoesQuietOnceItsNotLinked() = runTest {
        val monitor = monitor()
        set(slack, OnDeviceMessengerState.Connecting)
        set(slack, OnDeviceMessengerState.Failed("invalid_auth"))
        set(slack, OnDeviceMessengerState.NotLinked)
        assertTrue(monitor.problems.value.isEmpty())
    }

    @Test
    fun neverLinkedNetworksStayQuiet() = runTest {
        val monitor = monitor()
        set(whatsApp, OnDeviceMessengerState.Connecting)
        set(whatsApp, OnDeviceMessengerState.NotLinked)
        assertTrue(monitor.problems.value.isEmpty())
    }

    @Test
    fun aLongReconnectStartsOverBeforeAskingTheUser() = runTest {
        val monitor = monitor()
        set(whatsApp, OnDeviceMessengerState.Connected("Remik"))
        set(whatsApp, OnDeviceMessengerState.Connecting)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(monitor.problems.value.isEmpty())

        // Back quickly the next time: nothing to report
        set(whatsApp, OnDeviceMessengerState.Connected("Remik"))
        set(whatsApp, OnDeviceMessengerState.Connecting)
        advanceTimeBy(3 * 60_000 + 1)
        runCurrent()
        assertEquals(1, whatsApp.reconnects)
        assertTrue(monitor.problems.value.isEmpty())

        // The fresh connection didn't help either
        advanceTimeBy(7 * 60_000)
        runCurrent()
        assertEquals(ConnectionProblem.Kind.RECONNECTING, monitor.problems.value.single().kind)
    }

    @Test
    fun reconnectingOnRequestTriesAgainAndAsksAgainIfItDoesntHelp() = runTest {
        val monitor = monitor()
        set(slack, OnDeviceMessengerState.Connected("Team"))
        set(slack, OnDeviceMessengerState.Connecting)
        advanceTimeBy(10 * 60_000 + 1)
        runCurrent()
        assertEquals(ConnectionProblem.Kind.RECONNECTING, monitor.problems.value.single().kind)

        monitor.reconnect()
        runCurrent()
        assertEquals(2, slack.reconnects)
        assertTrue(monitor.problems.value.isEmpty())

        advanceTimeBy(7 * 60_000 + 1)
        runCurrent()
        assertEquals(ConnectionProblem.Kind.RECONNECTING, monitor.problems.value.single().kind)

        set(slack, OnDeviceMessengerState.Connected("Team"))
        assertTrue(monitor.problems.value.isEmpty())
    }

    @Test
    fun aReconnectThatDoesntGetItBackWithinAMinuteIsMarked() = runTest {
        val monitor = monitor()
        set(slack, OnDeviceMessengerState.Connected("Team"))
        set(slack, OnDeviceMessengerState.Connecting)
        advanceTimeBy(10 * 60_000 + 1)
        runCurrent()
        assertFalse(monitor.problems.value.single().reconnectDidntHelp)

        monitor.reconnect()
        runCurrent()
        advanceTimeBy(60_000 + 1)
        runCurrent()
        val problem = monitor.problems.value.single()
        assertEquals(ConnectionProblem.Kind.RECONNECTING, problem.kind)
        assertTrue(problem.reconnectDidntHelp)

        set(slack, OnDeviceMessengerState.Connected("Team"))
        assertTrue(monitor.problems.value.isEmpty())
        // Later trouble starts unmarked again
        set(slack, OnDeviceMessengerState.Failed("No network"))
        assertFalse(monitor.problems.value.single().reconnectDidntHelp)
    }

    @Test
    fun aFailedNetworkThatFailsAgainAfterReconnectIsMarked() = runTest {
        val monitor = monitor()
        set(whatsApp, OnDeviceMessengerState.Failed("No network"))
        assertFalse(monitor.problems.value.single().reconnectDidntHelp)

        monitor.reconnect()
        runCurrent()
        assertEquals(1, whatsApp.reconnects)
        // Hidden while it tries
        assertTrue(monitor.problems.value.isEmpty())

        set(whatsApp, OnDeviceMessengerState.Connecting)
        set(whatsApp, OnDeviceMessengerState.Failed("Still no network"))
        val problem = monitor.problems.value.single()
        assertTrue(problem.reconnectDidntHelp)
        assertEquals("Still no network", problem.detail)
    }

    @Test
    fun linkedNetworksAreTheOnesLoggedIn() = runTest {
        val monitor = monitor()
        assertTrue(monitor.linkedNetworks.value.isEmpty())
        set(slack, OnDeviceMessengerState.Connected("Team"))
        set(whatsApp, OnDeviceMessengerState.Failed("No network"))
        assertEquals(setOf(ChatNetwork.SLACK, ChatNetwork.WHATSAPP), monitor.linkedNetworks.value)

        set(slack, OnDeviceMessengerState.NotLinked)
        assertEquals(setOf(ChatNetwork.WHATSAPP), monitor.linkedNetworks.value)
    }

    @Test
    fun anUnverifiedAccountIsReportedUntilItSends() = runTest {
        val monitor = monitor()
        set(slack, OnDeviceMessengerState.Connected("Remi"))
        set(slack, OnDeviceMessengerState.Connected("Remi", needsVerification = true))
        assertEquals(ConnectionProblem.Kind.UNVERIFIED, monitor.problems.value.single().kind)

        set(slack, OnDeviceMessengerState.Connected("Remi"))
        assertTrue(monitor.problems.value.isEmpty())
    }

    @Test
    fun onlyDroppedNetworksStartOverWhenBackOnline() = runTest {
        val monitor = monitor()
        // Connecting for the first time: left alone
        set(whatsApp, OnDeviceMessengerState.Connecting)
        // Lost its connection
        set(slack, OnDeviceMessengerState.Connected("Team"))
        set(slack, OnDeviceMessengerState.Connecting)

        monitor.reconnectDropped()
        runCurrent()
        assertEquals(0, whatsApp.reconnects)
        assertEquals(1, slack.reconnects)
    }
}
