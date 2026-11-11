package com.devvalor.howyoudoing.app.notifications

import com.devvalor.howyoudoing.core.domain.app.NotificationSettings
import com.devvalor.howyoudoing.chat.presentation.model.accountTag
import com.devvalor.howyoudoing.core.domain.ondevice.AccountLabel
import com.devvalor.howyoudoing.core.domain.ondevice.AccountRef
import com.devvalor.howyoudoing.core.domain.ondevice.AccountTags
import howyoudoin.composeapp.generated.resources.Res
import howyoudoin.composeapp.generated.resources.notification_new_message
import org.jetbrains.compose.resources.getString
import com.devvalor.howyoudoing.chat.domain.Chat
import com.devvalor.howyoudoing.chat.domain.CallOutcome
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.Message
import com.devvalor.howyoudoing.chat.domain.NotificationPreferences
import com.devvalor.howyoudoing.chat.domain.OpenChatTracker
import com.devvalor.howyoudoing.chat.presentation.model.toChatUi
import com.devvalor.howyoudoing.settings.domain.AppLockRepository
import com.devvalor.howyoudoing.settings.domain.AppLockSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Shows and clears message notifications (Android notifications; nothing yet on iOS). */
interface MessageNotificationPresenter {
    /**
     * [silent] updates a notification without sounding or vibrating again (a ringing call that ended); [isCall] shows
     * it as a call, which has its own sound and can be turned off apart from messages.
     */
    @Suppress("LongParameterList") // One notification's parts
    fun show(
        chatId: String,
        title: String,
        text: String,
        timestampMs: Long,
        silent: Boolean = false,
        isCall: Boolean = false,
    )
    fun cancel(chatId: String)
}

/** A notification the user tapped: the app opens this chat, or the networks screen. */
object NotificationRouter {
    val pendingChatId = MutableStateFlow<String?>(null)
    val pendingNetworks = MutableStateFlow(false)
}

/**
 * Notifies about new incoming messages in any chat (server or on-device) that isn't muted or open on screen.
 * Chats are compared with what was seen before, so already-known messages (and the first load after starting)
 * never notify.
 */
@OptIn(ExperimentalTime::class)
@Suppress("LongParameterList") // Koin-injected collaborators
class IncomingMessageNotifier(
    chatRepository: ChatRepository,
    private val openChatTracker: OpenChatTracker,
    notificationPreferences: NotificationPreferences,
    private val presenter: MessageNotificationPresenter,
    appLock: AppLockRepository,
    notificationSettings: NotificationSettings,
    accountTags: AccountTags,
    scope: CoroutineScope
) {
    /** The accounts to name in notifications (their network has several here). */
    private var tags: Map<AccountRef, AccountLabel?> = emptyMap()

    private val lastSeenMessage = HashMap<String, String>()

    /**
     * Calls notified, by message ID, with the state last shown. A call keeps its message ID when it changes, so this
     * is how its notification learns that "Incoming call" became "Missed call", or that the phone answered it after
     * all (WhatsApp says so only after the call stopped ringing here).
     */
    private val notifiedCalls = HashMap<String, CallOutcome>()
    private val startedAt = now()

    init {
        accountTags.observe().onEach { tags = it }.launchIn(scope)
        combine(
            chatRepository.observeChats(),
            notificationPreferences.observeMutedChats(),
            appLock.observeSettings(),
            notificationSettings.observe(),
        ) { chats, muted, lock, choices ->
            Snapshot(chats, muted, lock, choices.messages, choices.calls)
        }
            .onEach { snapshot ->
                val (chats, muted, lock) = snapshot
                val warmingUp = now() - startedAt < WARM_UP_MILLIS
                for (chat in chats) {
                    val message = chat.lastMessage ?: continue
                    val previous = lastSeenMessage.put(chat.id, message.id)
                    val isNew = !warmingUp && previous != message.id && !message.isOutgoing &&
                        now() - message.timestamp <= MAX_AGE_MILLIS
                    val callState = message.call?.state
                    // Settings → Notifications: calls and messages are turned on and off apart
                    val isKindOn = if (message.call != null) snapshot.callsOn else snapshot.messagesOn
                    val isWanted = isKindOn && chat.id !in muted && !openChatTracker.isWatching(chat.id)
                    val shownState = notifiedCalls[message.id]
                    when {
                        isNew && isWanted -> {
                            notify(chat, message, lock)
                            if (callState != null && callState != CallOutcome.ENDED) {
                                notifiedCalls[message.id] = callState
                            }
                        }

                        callState == null || shownState == null || callState == shownState -> Unit

                        callState == CallOutcome.ENDED && shownState != CallOutcome.STARTED -> {
                            // Answered on the phone: nothing left to tell
                            notifiedCalls.remove(message.id)
                            presenter.cancel(chat.id)
                        }

                        isWanted -> {
                            // A call one joins (a huddle, an Instagram call) that ended: whether the user joined
                            // isn't known, so its notification stays, saying it's over
                            if (callState == CallOutcome.ENDED) {
                                notifiedCalls.remove(message.id)
                            } else {
                                notifiedCalls[message.id] = callState
                            }
                            notify(chat, message, lock, silent = true)
                        }
                    }
                }
            }
            .launchIn(scope)
        // Opening a chat clears its notification
        openChatTracker.openChatId.filterNotNull().onEach(presenter::cancel).launchIn(scope)
    }

    private suspend fun notify(chat: Chat, message: Message, lock: AppLockSettings, silent: Boolean = false) {
        val isCall = message.call != null
        if (lock.isEnabled && lock.hidesNotificationContent) {
            // With the app locked, a notification shouldn't reveal what the lock protects
            presenter.show(
                chat.id, "HowYouDoin", getString(Res.string.notification_new_message), message.timestamp, silent, isCall
            )
        } else {
            // "Anna · Work" when the network has several accounts here
            val account = chat.takeIf { it.isOnDevice }
                ?.let { accountTag(AccountRef(it.network, it.accountSlot), tags) }
            val title = account?.let { "${chat.name} · ${it.name.asStringAsync()}" } ?: chat.name
            presenter.show(chat.id, title, chat.toChatUi().preview.asStringAsync(), message.timestamp, silent, isCall)
        }
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private data class Snapshot(
        val chats: List<Chat>,
        val muted: Set<String>,
        val lock: AppLockSettings,
        val messagesOn: Boolean,
        val callsOn: Boolean,
    )

    private companion object {
        /** The inbox loads in bursts after starting; those aren't new messages. */
        const val WARM_UP_MILLIS = 15_000L

        /** Older messages showing up (history, reconnect catch-up) don't notify. */
        const val MAX_AGE_MILLIS = 5 * 60_000L
    }
}

/** Used where notifications aren't wired up yet. */
object NoOpNotificationPresenter : MessageNotificationPresenter {
    @Suppress("LongParameterList")
    override fun show(
        chatId: String,
        title: String,
        text: String,
        timestampMs: Long,
        silent: Boolean,
        isCall: Boolean,
    ) = Unit
    override fun cancel(chatId: String) = Unit
}
