package com.devvalor.howyoudoing.app.notifications

import howyoudoin.composeapp.generated.resources.Res
import howyoudoin.composeapp.generated.resources.notification_new_message
import org.jetbrains.compose.resources.getString
import com.devvalor.howyoudoing.chat.domain.Chat
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
    fun show(chatId: String, title: String, text: String, timestampMs: Long)
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
class IncomingMessageNotifier(
    chatRepository: ChatRepository,
    private val openChatTracker: OpenChatTracker,
    notificationPreferences: NotificationPreferences,
    private val presenter: MessageNotificationPresenter,
    appLock: AppLockRepository,
    scope: CoroutineScope
) {
    private val lastSeenMessage = HashMap<String, String>()
    private val startedAt = now()

    init {
        combine(
            chatRepository.observeChats(),
            notificationPreferences.observeMutedChats(),
            appLock.observeSettings()
        ) { chats, muted, lock ->
            Triple(chats, muted, lock)
        }
            .onEach { (chats, muted, lock) ->
                val warmingUp = now() - startedAt < WARM_UP_MILLIS
                for (chat in chats) {
                    val message = chat.lastMessage ?: continue
                    val previous = lastSeenMessage.put(chat.id, message.id)
                    val isNew = !warmingUp && previous != message.id && !message.isOutgoing &&
                        now() - message.timestamp <= MAX_AGE_MILLIS
                    val isWanted = chat.id !in muted && !openChatTracker.isWatching(chat.id)
                    if (isNew && isWanted) notify(chat, message, lock)
                }
            }
            .launchIn(scope)
        // Opening a chat clears its notification
        openChatTracker.openChatId.filterNotNull().onEach(presenter::cancel).launchIn(scope)
    }

    private suspend fun notify(chat: Chat, message: Message, lock: AppLockSettings) {
        if (lock.isEnabled && lock.hidesNotificationContent) {
            // With the app locked, a notification shouldn't reveal what the lock protects
            presenter.show(chat.id, "HowYouDoin", getString(Res.string.notification_new_message), message.timestamp)
        } else {
            presenter.show(chat.id, chat.name, chat.toChatUi().preview.asStringAsync(), message.timestamp)
        }
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private companion object {
        /** The inbox loads in bursts after starting; those aren't new messages. */
        const val WARM_UP_MILLIS = 15_000L

        /** Older messages showing up (history, reconnect catch-up) don't notify. */
        const val MAX_AGE_MILLIS = 5 * 60_000L
    }
}

/** Used where notifications aren't wired up yet. */
object NoOpNotificationPresenter : MessageNotificationPresenter {
    override fun show(chatId: String, title: String, text: String, timestampMs: Long) = Unit
    override fun cancel(chatId: String) = Unit
}
