package com.relay.chat.data.di

import com.relay.chat.data.MatrixChatRepository
import com.relay.chat.data.SessionAwareChatRepository
import com.relay.chat.data.demo.DemoChatRepository
import com.relay.chat.data.matrix.MatrixApi
import com.relay.chat.data.matrix.MatrixSyncEngine
import com.relay.chat.domain.ChatRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val chatDataModule = module {
    singleOf(::MatrixApi)
    singleOf(::MatrixSyncEngine)
    singleOf(::MatrixChatRepository)
    singleOf(::DemoChatRepository)
    singleOf(::SessionAwareChatRepository) bind ChatRepository::class
}
