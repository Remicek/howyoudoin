package com.devvalor.howyoudoing.chat.data.di

import com.devvalor.howyoudoing.chat.data.MatrixChatRepository
import com.devvalor.howyoudoing.chat.data.SessionAwareChatRepository
import com.devvalor.howyoudoing.chat.data.demo.DemoChatRepository
import com.devvalor.howyoudoing.chat.data.matrix.MatrixApi
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceChatRepository
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceMessengerStore
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceSource
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceMessenger
import com.devvalor.howyoudoing.core.domain.ondevice.OnDeviceWhatsApp
import org.koin.core.qualifier.named
import com.devvalor.howyoudoing.chat.data.matrix.MatrixSyncEngine
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val chatDataModule = module {
    singleOf(::MatrixApi)
    singleOf(::MatrixSyncEngine)
    singleOf(::MatrixChatRepository)
    singleOf(::DemoChatRepository)
    singleOf(::SessionAwareChatRepository)
    // Created eagerly so they collect the on-device inboxes from the start
    single(named(MESSENGER_STORE), createdAtStart = true) { OnDeviceMessengerStore(get<OnDeviceMessenger>(), get(), get()) }
    single(named(WHATSAPP_STORE), createdAtStart = true) {
        OnDeviceMessengerStore(
            get<OnDeviceWhatsApp>(), get(named(ON_DEVICE_WHATSAPP_CACHE)), get(), OnDeviceMessengerStore.WHATSAPP_CHAT_ID_PREFIX
        )
    }
    single {
        OnDeviceChatRepository(
            server = get(),
            sources = listOf(
                OnDeviceSource(get<OnDeviceMessenger>(), get(named(MESSENGER_STORE))),
                OnDeviceSource(get<OnDeviceWhatsApp>(), get(named(WHATSAPP_STORE)))
            ),
            httpClient = get()
        )
    } bind ChatRepository::class
}

private const val MESSENGER_STORE = "onDeviceMessengerStore"
private const val WHATSAPP_STORE = "onDeviceWhatsAppStore"

/** Koin name of the WhatsApp inbox cache (the Messenger one is unnamed). */
const val ON_DEVICE_WHATSAPP_CACHE = "onDeviceWhatsAppCache"

