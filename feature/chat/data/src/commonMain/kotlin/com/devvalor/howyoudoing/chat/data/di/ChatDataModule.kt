package com.devvalor.howyoudoing.chat.data.di

import com.devvalor.howyoudoing.chat.data.MatrixChatRepository
import com.devvalor.howyoudoing.chat.data.SessionAwareChatRepository
import com.devvalor.howyoudoing.chat.data.demo.DemoChatRepository
import com.devvalor.howyoudoing.chat.data.matrix.MatrixApi
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceChatRepository
import com.devvalor.howyoudoing.chat.data.ondevice.OnDeviceMessengerStore
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
    // Created eagerly so it collects the on-device Messenger inbox from the start
    single(createdAtStart = true) { OnDeviceMessengerStore(get(), get(), get()) }
    singleOf(::OnDeviceChatRepository) bind ChatRepository::class
}
