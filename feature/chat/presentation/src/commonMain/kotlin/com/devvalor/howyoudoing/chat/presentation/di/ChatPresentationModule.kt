package com.devvalor.howyoudoing.chat.presentation.di

import com.devvalor.howyoudoing.chat.presentation.conversation.ConversationViewModel
import com.devvalor.howyoudoing.chat.presentation.inbox.InboxViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val chatPresentationModule = module {
    viewModelOf(::InboxViewModel)
    viewModelOf(::ConversationViewModel)
}
