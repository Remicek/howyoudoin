package com.devvalor.howyoudoing.chat.data.ondevice

import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import com.devvalor.howyoudoing.core.domain.ondevice.DuplicateAccount
import com.devvalor.howyoudoing.core.domain.ondevice.DuplicateAccounts
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessengerState
import com.devvalor.howyoudoing.core.domain.ondevice.account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Tells logins apart by the account's own ID on its network (the "self" event each store keeps). When two slots of
 * one network carry the same ID, the one linked later is disconnected (with equal standing, the higher slot).
 */
class StoreDuplicateAccounts(
    private val sources: List<OnDeviceSource>,
    private val monitor: ConnectionMonitor,
    private val scope: CoroutineScope,
    /** When each account last got linked, for telling which login is the newer one; a counter in tests. */
    private val clock: () -> Long
) : DuplicateAccounts {

    private val _rejected = MutableSharedFlow<DuplicateAccount>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val rejected: Flow<DuplicateAccount> = _rejected.asSharedFlow()

    private val linkedAt = mutableMapOf<AccountRef, Long>()
    private val dropping = mutableSetOf<AccountRef>()

    private data class Login(val source: OnDeviceSource, val selfId: String?, val isLinked: Boolean)

    init {
        if (sources.isNotEmpty()) {
            combine(sources.map { source -> login(source) }) { it.toList() }
                .onEach(::check)
                .launchIn(scope)
        }
    }

    private fun login(source: OnDeviceSource): Flow<Login> =
        combine(source.store.snapshot.map { it.selfId }, source.client.state) { selfId, state ->
            val isLinked = state != OnDeviceMessengerState.NotLinked
            val account = source.client.account
            if (!isLinked) {
                linkedAt.remove(account)
                dropping.remove(account)
            } else if (account !in linkedAt) {
                linkedAt[account] = clock()
            }
            Login(source, selfId?.takeIf { it.isNotEmpty() && isLinked }, isLinked)
        }.distinctUntilChanged()

    private fun check(logins: List<Login>) {
        logins.filter { it.selfId != null }
            .groupBy { it.source.client.network to it.selfId }
            .values
            .filter { it.size > 1 }
            .forEach { same ->
                val ordered = same.sortedWith(
                    compareBy({ linkedAt[it.source.client.account] ?: 0L }, { it.source.client.slot })
                )
                val kept = ordered.first().source.client.account
                ordered.drop(1).forEach { drop(it.source, kept) }
            }
    }

    private fun drop(source: OnDeviceSource, kept: AccountRef) {
        val account = source.client.account
        if (!dropping.add(account)) return
        scope.launch {
            monitor.expectDisconnect(account)
            source.client.unlink()
            _rejected.emit(DuplicateAccount(account, kept))
        }
    }
}
