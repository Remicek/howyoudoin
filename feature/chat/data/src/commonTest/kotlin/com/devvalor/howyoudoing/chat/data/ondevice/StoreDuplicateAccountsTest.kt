package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import com.devvalor.howyoudoing.core.domain.ondevice.DuplicateAccount
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.ondevice.UnsupportedOnDeviceMessenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoreDuplicateAccountsTest {

    private class FakeClient(override val network: ChatNetwork, override val slot: Int) :
        OnDeviceMessenger by UnsupportedOnDeviceMessenger {
        override val isSupported = true
        override val state = MutableStateFlow<OnDeviceMessengerState>(OnDeviceMessengerState.NotLinked)
        override val events = MutableSharedFlow<String>(extraBufferCapacity = 64)
        var unlinked = false
        override suspend fun unlink() {
            unlinked = true
            state.value = OnDeviceMessengerState.NotLinked
        }
    }

    private class MemoryCache : OnDeviceInboxCache {
        override fun read(): String? = null
        override fun write(value: String) = Unit
        override fun clear() = Unit
    }

    private val first = FakeClient(ChatNetwork.TELEGRAM, 0)
    private val second = FakeClient(ChatNetwork.TELEGRAM, 1)
    private val otherNetwork = FakeClient(ChatNetwork.SIGNAL, 0)
    private var now = 0L

    private fun TestScope.guard(): MutableList<DuplicateAccount> {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val clients = listOf(first, second, otherNetwork)
        val sources = clients.map { client ->
            OnDeviceSource(
                client,
                OnDeviceMessengerStore(
                    client,
                    MemoryCache(),
                    scope,
                    OnDeviceMessengerStore.chatIdPrefix(client.network, client.slot)
                )
            )
        }
        val monitor = ConnectionMonitor(clients, scope, UnconfinedTestDispatcher(testScheduler))
        val rejected = mutableListOf<DuplicateAccount>()
        StoreDuplicateAccounts(sources, monitor, scope) { now++ }.rejected.onEach { rejected += it }.launchIn(scope)
        runCurrent()
        return rejected
    }

    private suspend fun TestScope.logIn(client: FakeClient, selfId: String) {
        client.state.value = OnDeviceMessengerState.Connected("Me")
        runCurrent()
        client.events.emit("""{"type":"self","id":"$selfId"}""")
        runCurrent()
    }

    @Test
    fun theSameAccountLoggedInAgainIsRemoved() = runTest {
        val rejected = guard()
        logIn(first, "42")
        logIn(second, "42")
        assertTrue(second.unlinked)
        assertEquals(false, first.unlinked)
        assertEquals(
            listOf(DuplicateAccount(AccountRef(ChatNetwork.TELEGRAM, 1), AccountRef(ChatNetwork.TELEGRAM))),
            rejected
        )
    }

    @Test
    fun differentAccountsStay() = runTest {
        val rejected = guard()
        logIn(first, "42")
        logIn(second, "43")
        // The same ID on another network is someone else
        logIn(otherNetwork, "42")
        assertEquals(false, second.unlinked)
        assertEquals(false, otherNetwork.unlinked)
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun theNewerLoginGoesEvenInTheFirstSlot() = runTest {
        val rejected = guard()
        logIn(second, "42")
        logIn(first, "42")
        assertTrue(first.unlinked)
        assertEquals(AccountRef(ChatNetwork.TELEGRAM), rejected.single().account)
    }
}
