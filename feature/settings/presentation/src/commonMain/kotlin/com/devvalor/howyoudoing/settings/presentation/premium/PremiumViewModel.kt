package com.devvalor.howyoudoing.settings.presentation.premium

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.core.domain.premium.CallAllowance
import com.devvalor.howyoudoing.core.domain.premium.CallMinutes
import com.devvalor.howyoudoing.core.domain.premium.Premium
import com.devvalor.howyoudoing.core.domain.premium.PremiumOffer
import com.devvalor.howyoudoing.core.domain.premium.PremiumProduct
import com.devvalor.howyoudoing.core.domain.premium.PurchaseOutcome
import com.devvalor.howyoudoing.core.domain.util.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PremiumMessage { THANKS, PENDING, FAILED, TOP_UP_ADDED }

data class PremiumState(
    val isPremium: Boolean = false,
    /** Null where Google Play doesn't sell it (not installed from Play) or hasn't answered yet. */
    val subscription: PremiumOffer? = null,
    val topUp: PremiumOffer? = null,
    /** Calls through a link exist in this build (else no minutes to show). */
    val hasCalls: Boolean = false,
    val minutes: CallMinutes? = null,
    val minutesFailed: Boolean = false,
    val isBuying: Boolean = false,
    val message: PremiumMessage? = null,
)

sealed interface PremiumAction {
    data object OnSubscribeClick : PremiumAction
    data object OnTopUpClick : PremiumAction
    data object OnRestoreClick : PremiumAction
    data object OnManageClick : PremiumAction
    data object OnMessageShown : PremiumAction
    data object OnBackClick : PremiumAction
}

class PremiumViewModel(private val premium: Premium, private val allowance: CallAllowance) : ViewModel() {

    private val ui = MutableStateFlow(PremiumState(hasCalls = allowance.isAvailable))

    val state = combine(ui, premium.status) { ui, status ->
        ui.copy(isPremium = status.isPremium, subscription = status.subscription, topUp = status.topUp)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ui.value)

    val manageUrl: String get() = premium.manageUrl

    init {
        viewModelScope.launch {
            premium.refresh()
            loadMinutes()
        }
        // Premium starting or ending, or a top-up counted, changes the minutes
        premium.status.map { it.isPremium to it.unclaimedTopUps.size }.distinctUntilChanged().drop(1)
            .onEach { loadMinutes() }
            .launchIn(viewModelScope)
    }

    fun onAction(action: PremiumAction) {
        when (action) {
            PremiumAction.OnSubscribeClick -> buy(PremiumProduct.SUBSCRIPTION)
            PremiumAction.OnTopUpClick -> buy(PremiumProduct.VIDEO_TOP_UP)
            PremiumAction.OnRestoreClick -> viewModelScope.launch {
                premium.refresh()
                loadMinutes()
            }
            PremiumAction.OnMessageShown -> ui.update { it.copy(message = null) }
            PremiumAction.OnManageClick, PremiumAction.OnBackClick -> Unit
        }
    }

    private fun buy(product: PremiumProduct) {
        if (ui.value.isBuying) return
        ui.update { it.copy(isBuying = true) }
        viewModelScope.launch {
            val message = when (premium.buy(product)) {
                PurchaseOutcome.Bought ->
                    if (product == PremiumProduct.SUBSCRIPTION) PremiumMessage.THANKS else PremiumMessage.TOP_UP_ADDED
                PurchaseOutcome.Pending -> PremiumMessage.PENDING
                PurchaseOutcome.AlreadyOwned, PurchaseOutcome.Cancelled -> null
                PurchaseOutcome.Failed -> PremiumMessage.FAILED
            }
            ui.update { it.copy(isBuying = false, message = message) }
            loadMinutes()
        }
    }

    private suspend fun loadMinutes() {
        if (!allowance.isAvailable) return
        when (val result = allowance.minutes()) {
            is Result.Success -> ui.update { it.copy(minutes = result.data, minutesFailed = false) }
            is Result.Failure -> ui.update { it.copy(minutesFailed = it.minutes == null) }
        }
    }
}
