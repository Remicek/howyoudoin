package com.devvalor.howyoudoing.app.premium

import com.devvalor.howyoudoing.core.domain.premium.CallAllowance
import com.devvalor.howyoudoing.core.domain.premium.CallMinutes
import com.devvalor.howyoudoing.core.domain.premium.Premium
import com.devvalor.howyoudoing.core.domain.premium.PremiumProduct
import com.devvalor.howyoudoing.core.domain.premium.PremiumStatus
import com.devvalor.howyoudoing.core.domain.premium.PurchaseOutcome
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TopUpClaimerTest {

    private class FakePremium : Premium {
        override val status = MutableStateFlow(PremiumStatus())
        override suspend fun buy(product: PremiumProduct) = PurchaseOutcome.Failed
        override suspend fun refresh() = Unit
        override suspend fun topUpClaimed(token: String) {
            status.update { it.copy(unclaimedTopUps = it.unclaimedTopUps - token) }
        }
        override val manageUrl = ""
    }

    private class FakeServer(var reachable: Boolean) : CallAllowance {
        val claimed = mutableListOf<String>()
        override val isAvailable = true
        override suspend fun minutes(): Result<CallMinutes, DataError.Remote> = error("not used")
        override suspend fun claimTopUp(token: String): Result<CallMinutes, DataError.Remote> {
            if (!reachable) return Result.Failure(DataError.Remote.NO_INTERNET)
            claimed += token
            return Result.Success(CallMinutes(false, 0, 0, 0, 0))
        }
    }

    @Test
    fun aPaidTopUpIsHandedToTheServerOnce() = runTest {
        val premium = FakePremium()
        val server = FakeServer(reachable = true)
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        TopUpClaimer(premium, server, scope)
        premium.status.update { it.copy(unclaimedTopUps = listOf("t1")) }
        runCurrent()
        assertEquals(listOf("t1"), server.claimed)
        assertEquals(emptyList(), premium.status.value.unclaimedTopUps)
    }

    @Test
    fun oneTheServerDidntGetStaysForNextTime() = runTest {
        val premium = FakePremium()
        val server = FakeServer(reachable = false)
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        TopUpClaimer(premium, server, scope)
        premium.status.update { it.copy(unclaimedTopUps = listOf("t1")) }
        runCurrent()
        assertEquals(listOf("t1"), premium.status.value.unclaimedTopUps)
        // Google Play lists it again later (the next launch, or opening Premium)
        server.reachable = true
        premium.status.update { it.copy(unclaimedTopUps = listOf("t1", "t2")) }
        runCurrent()
        assertEquals(listOf("t1", "t2"), server.claimed)
    }
}
