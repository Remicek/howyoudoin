package com.devvalor.howyoudoing.chat.data.di

import com.devvalor.howyoudoing.chat.data.MatrixChatRepository
import com.devvalor.howyoudoing.chat.data.LocalOnlyChatRepository
import com.devvalor.howyoudoing.chat.data.SessionAwareChatRepository
import com.devvalor.howyoudoing.chat.data.demo.DemoChatRepository
import com.devvalor.howyoudoing.chat.data.matrix.MatrixApi
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceChatRepository
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceMessengerStore
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceSource
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceInstagram
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceWhatsApp
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceSignal
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceTelegram
import org.koin.core.qualifier.named
import com.devvalor.howyoudoing.chat.data.matrix.MatrixSyncEngine
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.chat.domain.NotificationPreferences
import com.devvalor.howyoudoing.chat.domain.OpenChatTracker
import com.devvalor.howyoudoing.chat.data.DataStoreNotificationPreferences
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val chatDataModule = module {
    singleOf(::MatrixApi)
    singleOf(::MatrixSyncEngine)
    singleOf(::MatrixChatRepository)
    singleOf(::DemoChatRepository)
    singleOf(::LocalOnlyChatRepository)
    singleOf(::SessionAwareChatRepository)
    singleOf(::OpenChatTracker)
    singleOf(::DataStoreNotificationPreferences) bind NotificationPreferences::class
    // Created eagerly so they collect the on-device inboxes from the start
    single(named(MESSENGER_STORE), createdAtStart = true) { OnDeviceMessengerStore(get<OnDeviceMessenger>(), get(), get()) }
    single(named(WHATSAPP_STORE), createdAtStart = true) {
        OnDeviceMessengerStore(
            get<OnDeviceWhatsApp>(), get(named(ON_DEVICE_WHATSAPP_CACHE)), get(), OnDeviceMessengerStore.WHATSAPP_CHAT_ID_PREFIX
        )
    }
    single(named(INSTAGRAM_STORE), createdAtStart = true) {
        OnDeviceMessengerStore(
            get<OnDeviceInstagram>(), get(named(ON_DEVICE_INSTAGRAM_CACHE)), get(), OnDeviceMessengerStore.INSTAGRAM_CHAT_ID_PREFIX
        )
    }
    single(named(TELEGRAM_STORE), createdAtStart = true) {
        OnDeviceMessengerStore(
            get<OnDeviceTelegram>(), get(named(ON_DEVICE_TELEGRAM_CACHE)), get(), OnDeviceMessengerStore.TELEGRAM_CHAT_ID_PREFIX
        )
    }
    single(named(SIGNAL_STORE), createdAtStart = true) {
        OnDeviceMessengerStore(
            get<OnDeviceSignal>(), get(named(ON_DEVICE_SIGNAL_CACHE)), get(), OnDeviceMessengerStore.SIGNAL_CHAT_ID_PREFIX
        )
    }
    single {
        OnDeviceChatRepository(
            server = get(),
            sources = listOf(
                OnDeviceSource(get<OnDeviceMessenger>(), get(named(MESSENGER_STORE))),
                OnDeviceSource(get<OnDeviceWhatsApp>(), get(named(WHATSAPP_STORE))),
                OnDeviceSource(get<OnDeviceInstagram>(), get(named(INSTAGRAM_STORE))),
                OnDeviceSource(get<OnDeviceTelegram>(), get(named(TELEGRAM_STORE))),
                OnDeviceSource(get<OnDeviceSignal>(), get(named(SIGNAL_STORE)))
            ),
            httpClient = get()
        )
    } bind ChatRepository::class
}

private const val MESSENGER_STORE = "onDeviceMessengerStore"
private const val WHATSAPP_STORE = "onDeviceWhatsAppStore"
private const val INSTAGRAM_STORE = "onDeviceInstagramStore"
private const val TELEGRAM_STORE = "onDeviceTelegramStore"
private const val SIGNAL_STORE = "onDeviceSignalStore"

/** Koin name of the WhatsApp inbox cache (the Messenger one is unnamed). */
const val ON_DEVICE_WHATSAPP_CACHE = "onDeviceWhatsAppCache"

/** Koin name of the Instagram inbox cache. */
const val ON_DEVICE_INSTAGRAM_CACHE = "onDeviceInstagramCache"

/** Koin name of the Telegram inbox cache. */
const val ON_DEVICE_TELEGRAM_CACHE = "onDeviceTelegramCache"

/** Koin name of the Signal inbox cache. */
const val ON_DEVICE_SIGNAL_CACHE = "onDeviceSignalCache"
