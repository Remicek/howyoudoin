package com.devvalor.howyoudoing.app.premium

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.devvalor.howyoudoing.core.domain.premium.Premium
import com.devvalor.howyoudoing.core.domain.premium.PremiumOffer
import com.devvalor.howyoudoing.core.domain.premium.PremiumProduct
import com.devvalor.howyoudoing.core.domain.premium.PremiumStatus
import com.devvalor.howyoudoing.core.domain.premium.PurchaseOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Premium and top-ups through Google Play Billing. Google Play only sells to builds installed from Play (or signed
 * like them, for testers): elsewhere the offers stay null and the screens say Premium isn't available.
 */
class PlayPremium(private val app: Application, private val scope: CoroutineScope) : Premium, PurchasesUpdatedListener {

    private val state = MutableStateFlow(PremiumStatus())
    override val status: StateFlow<PremiumStatus> = state.asStateFlow()

    override val manageUrl = "https://play.google.com/store/account/subscriptions" +
        "?sku=${PremiumProduct.SUBSCRIPTION.id}&package=${app.packageName}"

    private val client = BillingClient.newBuilder(app)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private val connecting = Mutex()
    private var products: Map<String, ProductDetails> = emptyMap()
    private var buying: CompletableDeferred<PurchaseOutcome>? = null
    /** Play's sheet needs the activity on screen. */
    private var shown = WeakReference<Activity>(null)

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                shown = WeakReference(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        scope.launch { refresh() }
    }

    private suspend fun connected(): Boolean = connecting.withLock {
        if (client.isReady) return true
        suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                    if (continuation.isActive) continuation.resume(ok)
                }

                override fun onBillingServiceDisconnected() {
                    if (continuation.isActive) continuation.resume(false)
                }
            })
        }
    }

    override suspend fun refresh() {
        if (!connected()) return
        val details = listOf(
            PremiumProduct.SUBSCRIPTION to BillingClient.ProductType.SUBS,
            PremiumProduct.VIDEO_TOP_UP to BillingClient.ProductType.INAPP,
        ).mapNotNull { (product, type) ->
            val query = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(product.id)
                .setProductType(type)
                .build()
            val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(query)).build()
            client.queryProductDetails(params).productDetailsList?.firstOrNull()
        }
        products = details.associateBy { it.productId }
        state.update {
            it.copy(
                subscription = products[PremiumProduct.SUBSCRIPTION.id]?.subscriptionOffer()?.toOffer(),
                topUp = products[PremiumProduct.VIDEO_TOP_UP.id]?.oneTimePurchaseOfferDetails
                    ?.let { offer -> PremiumOffer(offer.formattedPrice) },
            )
        }
        val subscriptions = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ).purchasesList
        val oneTime = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        ).purchasesList
        apply(subscriptions + oneTime, replaceSubscription = true)
    }

    /** What these purchases mean; [replaceSubscription]: they're everything Play has, so no subscription is none. */
    private fun apply(purchases: List<Purchase>, replaceSubscription: Boolean) {
        val paid = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val subscription = paid.firstOrNull { PremiumProduct.SUBSCRIPTION.id in it.products }
        val topUps = paid.filter { PremiumProduct.VIDEO_TOP_UP.id in it.products }.map { it.purchaseToken }
        subscription?.takeIf { !it.isAcknowledged }?.let { purchase ->
            scope.launch {
                // The call server acknowledges it too, in case this doesn't get through
                runCatching {
                    client.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                    )
                }
            }
        }
        state.update {
            it.copy(
                isPremium = if (subscription != null || replaceSubscription) subscription != null else it.isPremium,
                subscriptionToken = subscription?.purchaseToken
                    ?: it.subscriptionToken.takeUnless { replaceSubscription },
                unclaimedTopUps = (it.unclaimedTopUps + topUps).distinct(),
            )
        }
    }

    override suspend fun buy(product: PremiumProduct): PurchaseOutcome {
        if (connected() && product.id !in products) refresh()
        val details = products[product.id]
        val activity = shown.get()
        return if (details == null || activity == null || !client.isReady) {
            PurchaseOutcome.Failed
        } else {
            launch(details, activity)
        }
    }

    private suspend fun launch(details: ProductDetails, activity: Activity): PurchaseOutcome {
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).apply {
            details.subscriptionOffer()?.let { setOfferToken(it.offerToken) }
        }.build()
        val outcome = CompletableDeferred<PurchaseOutcome>()
        buying?.complete(PurchaseOutcome.Cancelled)
        buying = outcome
        val launched = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build(),
        )
        if (launched.responseCode != BillingClient.BillingResponseCode.OK) {
            buying = null
            outcome.complete(launched.toOutcome())
        }
        return outcome.await()
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            apply(purchases, replaceSubscription = false)
        }
        if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) scope.launch { refresh() }
        val outcome = when {
            result.responseCode != BillingClient.BillingResponseCode.OK -> result.toOutcome()
            purchases.orEmpty().any { it.purchaseState == Purchase.PurchaseState.PENDING } -> PurchaseOutcome.Pending
            else -> PurchaseOutcome.Bought
        }
        buying?.complete(outcome)
        buying = null
    }

    override suspend fun topUpClaimed(token: String) {
        // The call server consumed it on Google Play, so it can be bought again
        state.update { it.copy(unclaimedTopUps = it.unclaimedTopUps - token) }
    }

    private fun BillingResult.toOutcome() = when (responseCode) {
        BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseOutcome.Cancelled
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseOutcome.AlreadyOwned
        else -> PurchaseOutcome.Failed
    }
}

/** The offer to sell: one with a free trial when Play offers this user one, else the plain monthly plan. */
private fun ProductDetails.subscriptionOffer(): ProductDetails.SubscriptionOfferDetails? {
    val offers = subscriptionOfferDetails.orEmpty()
    return offers.firstOrNull { offer -> offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
        ?: offers.firstOrNull { it.offerId == null }
        ?: offers.firstOrNull()
}

private fun ProductDetails.SubscriptionOfferDetails.toOffer(): PremiumOffer? {
    val phases = pricingPhases.pricingPhaseList
    val paid = phases.lastOrNull { it.priceAmountMicros > 0 } ?: return null
    val trial = phases.firstOrNull { it.priceAmountMicros == 0L }
    return PremiumOffer(price = paid.formattedPrice, trialDays = trial?.let { periodDays(it.billingPeriod) })
}

/** ISO 8601 periods as Play writes them: "P7D", "P1W", "P1M". */
internal fun periodDays(period: String): Int? {
    val match = Regex("""P(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?""").matchEntire(period) ?: return null
    val (months, weeks, days) = match.destructured
    val total = (months.toIntOrNull() ?: 0) * DAYS_IN_MONTH + (weeks.toIntOrNull() ?: 0) * DAYS_IN_WEEK +
        (days.toIntOrNull() ?: 0)
    return total.takeIf { it > 0 }
}

private const val DAYS_IN_WEEK = 7

/** Play sells monthly trials as "P1M"; a month counts as 30 days here. */
private const val DAYS_IN_MONTH = 30
