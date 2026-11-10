package com.devvalor.howyoudoing.core.domain.ondevice

import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AccountSlotsTest {

    @Test
    fun accountKeysRoundTrip() {
        val account = AccountRef(ChatNetwork.GOOGLE_MESSAGES, 2)
        assertEquals("GOOGLE_MESSAGES/2", account.key)
        assertEquals(account, AccountRef.fromKey(account.key))
    }

    @Test
    fun badKeysAreIgnored() {
        assertNull(AccountRef.fromKey("WHATSAPP"))
        assertNull(AccountRef.fromKey("NOPE/1"))
        assertNull(AccountRef.fromKey("WHATSAPP/3"))
        assertNull(AccountRef.fromKey("WHATSAPP/x"))
    }

    @Test
    fun onlyThreeSlots() {
        assertFailsWith<IllegalArgumentException> { AccountRef(ChatNetwork.WHATSAPP, MAX_ACCOUNTS_PER_NETWORK) }
    }

    private class FakeClient(override val network: ChatNetwork, override val slot: Int) :
        OnDeviceMessenger by UnsupportedOnDeviceMessenger {
        override val state = MutableStateFlow<OnDeviceMessengerState>(OnDeviceMessengerState.Connected("x"))
    }

    private class FakeLabels(initial: Map<AccountRef, AccountLabel>) : AccountLabels {
        val labels = MutableStateFlow(initial)
        override fun observe(): Flow<Map<AccountRef, AccountLabel>> = labels
        override suspend fun set(account: AccountRef, label: AccountLabel) {
            labels.value += account to label
        }

        override suspend fun remove(account: AccountRef) {
            labels.value -= account
        }
    }

    @Test
    fun onlyNetworksWithSeveralAccountsAreTagged() = runTest {
        val work = AccountRef(ChatNetwork.WHATSAPP, 1)
        val monitor = ConnectionMonitor(
            listOf(
                FakeClient(ChatNetwork.WHATSAPP, 0),
                FakeClient(ChatNetwork.WHATSAPP, 1),
                FakeClient(ChatNetwork.SLACK, 0)
            ),
            backgroundScope,
            StandardTestDispatcher(testScheduler)
        )
        runCurrent()
        val tags = AccountTags(monitor, FakeLabels(mapOf(work to AccountLabel("Work", 3)))).observe().first()
        assertEquals(mapOf(AccountRef(ChatNetwork.WHATSAPP) to null, work to AccountLabel("Work", 3)), tags)
    }
}
