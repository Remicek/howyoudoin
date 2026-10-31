package com.devvalor.howyoudoing.chat.presentation.di

import com.devvalor.howyoudoing.chat.presentation.contacts.ContactsViewModel
import org.koin.core.module.dsl.viewModel
import com.devvalor.howyoudoing.chat.presentation.conversation.ConversationViewModel
import com.devvalor.howyoudoing.chat.presentation.inbox.InboxViewModel
import com.devvalor.howyoudoing.chat.presentation.inbox.MatrixKeysViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val chatPresentationModule = module {
    viewModelOf(::InboxViewModel)
    viewModelOf(::ConversationViewModel)
    viewModelOf(::MatrixKeysViewModel)
    viewModel { (isNewMessage: Boolean) -> ContactsViewModel(get(), get(), isNewMessage) }
}
