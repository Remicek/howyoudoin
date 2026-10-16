package com.relay.chat.presentation.di

import com.relay.chat.presentation.conversation.ConversationViewModel
import com.relay.chat.presentation.inbox.InboxViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val chatPresentationModule = module {
    viewModelOf(::InboxViewModel)
    viewModelOf(::ConversationViewModel)
}
