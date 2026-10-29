package com.devvalor.howyoudoing.chat.presentation.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.chat.domain.ChatContact
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.presentation.util.UiText
import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.contacts_cannot_start
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContactsState(
    /** Writing a new message: pick the network first, then the person. Otherwise the Contacts tab. */
    val isNewMessage: Boolean = false,
    /** Networks that have people to write to. */
    val networks: List<ChatNetwork> = emptyList(),
    /** The chosen network; null shows every network's people (Contacts) or the network picker (New message). */
    val network: ChatNetwork? = null,
    val query: String = "",
    val contacts: List<ChatContact> = emptyList(),
    val isLoading: Boolean = true,
    val error: UiText? = null
) {
    val isPickingNetwork: Boolean get() = isNewMessage && network == null
}

sealed interface ContactsAction {
    data class OnNetworkSelect(val network: ChatNetwork?) : ContactsAction
    data class OnQueryChange(val query: String) : ContactsAction
    data class OnContactClick(val contact: ChatContact) : ContactsAction
    data object OnBackClick : ContactsAction
}

sealed interface ContactsEvent {
    data class OpenChat(val chatId: String) : ContactsEvent
    data object Close : ContactsEvent
}

/**
 * People to write to, by name, from what the connected networks told the app (see ChatRepository.observeContacts,
 * which also notes the TODO for searching beyond them).
 */
class ContactsViewModel(
    private val chatRepository: ChatRepository,
    isNewMessage: Boolean
) : ViewModel() {

    private val ui = MutableStateFlow(ContactsState(isNewMessage = isNewMessage))
    private val events = Channel<ContactsEvent>()
    val eventFlow = events.receiveAsFlow()

    val state = combine(ui, chatRepository.observeContacts()) { s, all ->
        val networks = all.map { it.network }.distinct().sortedBy { it.ordinal }
        // With one network there's nothing to choose
        val network = s.network ?: networks.singleOrNull()?.takeIf { s.isNewMessage }
        val query = s.query.trim()
        val shown = all
            .filter { network == null || it.network == network }
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        val chosen = s.copy(network = network)
        chosen.copy(
            networks = networks,
            contacts = if (chosen.isPickingNetwork) emptyList() else shown,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ui.value)

    fun onAction(action: ContactsAction) {
        when (action) {
            is ContactsAction.OnNetworkSelect -> ui.update {
                it.copy(network = action.network, query = "", error = null)
            }
            is ContactsAction.OnQueryChange -> ui.update { it.copy(query = action.query) }
            is ContactsAction.OnContactClick -> startChat(action.contact)
            ContactsAction.OnBackClick -> back()
        }
    }

    /** Back from a chosen network to the network picker, else away. */
    private fun back() {
        val s = ui.value
        if (s.isNewMessage && s.network != null && s.networks.size != 1) {
            ui.update { it.copy(network = null, query = "", error = null) }
        } else {
            viewModelScope.launch { events.send(ContactsEvent.Close) }
        }
    }

    private fun startChat(contact: ChatContact) {
        viewModelScope.launch {
            when (val result = chatRepository.startChat(contact)) {
                is Result.Success -> events.send(ContactsEvent.OpenChat(result.data))
                is Result.Failure -> ui.update {
                    val network = contact.network.displayName
                    it.copy(error = UiText.Resource(Res.string.contacts_cannot_start, arrayOf(network)))
                }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
