package com.devvalor.howyoudoing.core.domain.premium

import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What can be bought on Google Play. */
enum class PremiumProduct(val id: String) {
    /** Premium, a month at a time. */
    SUBSCRIPTION("premium_monthly"),

    /** 5 hours of call video that never expire. */
    VIDEO_TOP_UP("video_5h"),
}

/** A product's price as Google Play shows it to this user ("$2.99", "2,99 zł"). */
data class PremiumOffer(
    val price: String,
    /** Days free before the first payment, when Play offers this user a trial. */
    val trialDays: Int? = null,
)

data class PremiumStatus(
    /** Paid up now (Google Play says so; the call server checks it again). */
    val isPremium: Boolean = false,
    /** For the call server to check; null without Premium. */
    val subscriptionToken: String? = null,
    /** Null until Google Play answers, or where it isn't available (no Play Store, iOS for now). */
    val subscription: PremiumOffer? = null,
    val topUp: PremiumOffer? = null,
    /** Top-ups paid for that the call server hasn't counted yet. */
    val unclaimedTopUps: List<String> = emptyList(),
)

sealed interface PurchaseOutcome {
    data object Bought : PurchaseOutcome
    /** Waiting for payment (e.g. cash at a shop): it's added once Play says it's paid. */
    data object Pending : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    data object AlreadyOwned : PurchaseOutcome
    data object Failed : PurchaseOutcome
}

/** Premium and top-ups through Google Play. */
interface Premium {
    val status: StateFlow<PremiumStatus>

    /** Opens Google Play's purchase sheet. */
    suspend fun buy(product: PremiumProduct): PurchaseOutcome

    /** Asks Google Play again (after coming back to the app, or "Restore"). */
    suspend fun refresh()

    /** The call server counted this top-up (it also consumes it on Google Play). */
    suspend fun topUpClaimed(token: String)

    /** Google Play's page for managing the subscription. */
    val manageUrl: String
}

/** Builds without Google Play: always the free plan. */
object NoPremium : Premium {
    private val state = MutableStateFlow(PremiumStatus())
    override val status: StateFlow<PremiumStatus> = state.asStateFlow()
    override suspend fun buy(product: PremiumProduct) = PurchaseOutcome.Failed
    override suspend fun refresh() = Unit
    override suspend fun topUpClaimed(token: String) = Unit
    override val manageUrl = "https://play.google.com/store/account/subscriptions"
}

/** This month's call minutes, as the call server counts them. */
data class CallMinutes(
    val isPremium: Boolean,
    val videoLeftMs: Long,
    val audioLeftMs: Long,
    /** Of videoLeftMs, bought time (never expires). */
    val topUpMs: Long,
    /** When the month's minutes start again. */
    val resetsAtMs: Long,
)

/** The call server's side of the plans: minutes left, and adding a bought top-up. */
interface CallAllowance {
    val isAvailable: Boolean
    suspend fun minutes(): Result<CallMinutes, DataError.Remote>
    suspend fun claimTopUp(token: String): Result<CallMinutes, DataError.Remote>
}

object NoCallAllowance : CallAllowance {
    override val isAvailable = false
    override suspend fun minutes(): Result<CallMinutes, DataError.Remote> =
        Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
    override suspend fun claimTopUp(token: String): Result<CallMinutes, DataError.Remote> =
        Result.Failure(DataError.Remote.SERVICE_UNAVAILABLE)
}

/** A random ID made once per install: whose free call minutes these are (the call server knows nothing else). */
fun interface InstallId {
    suspend fun get(): String
}

/** What the free plan and Premium include, for the screens (the call server enforces it: call-link/src/plans.ts). */
object Plans {
    const val FREE_VIDEO_MINUTES = 60
    const val FREE_AUDIO_HOURS = 10
    const val FREE_CALL_MINUTES = 60
    const val FREE_VIDEO_HEIGHT = 360
    const val PREMIUM_VIDEO_HOURS = 50
    const val PREMIUM_AUDIO_HOURS = 100
    const val PREMIUM_CALL_HOURS = 4
    const val PREMIUM_VIDEO_HEIGHT = 540
    const val TOP_UP_VIDEO_HOURS = 5
}
