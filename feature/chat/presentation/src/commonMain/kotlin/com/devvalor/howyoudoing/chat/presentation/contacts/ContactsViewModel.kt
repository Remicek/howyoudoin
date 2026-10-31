package com.devvalor.howyoudoing.chat.presentation.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devvalor.howyoudoing.chat.domain.ChatContact
import com.devvalor.howyoudoing.chat.domain.ChatRepository
import com.devvalor.howyoudoing.core.domain.ondevice.ConnectionMonitor
import com.devvalor.howyoudoing.chat.domain.MediaRequest
import com.devvalor.howyoudoing.core.domain.network.ChatNetwork
import com.devvalor.howyoudoing.core.domain.util.DataError
import com.devvalor.howyoudoing.core.domain.util.Result
import com.devvalor.howyoudoing.core.presentation.util.UiText
import howyoudoin.feature.chat.presentation.generated.resources.Res
import howyoudoin.feature.chat.presentation.generated.resources.contacts_cannot_start
import howyoudoin.feature.chat.presentation.generated.resources.contacts_search_failed
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /** People a search of the chosen network found beyond [contacts] (new message only). */
    val found: List<ChatContact> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: UiText? = null,
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
 * People to write to, by name, from what the connected networks told the app (ChatRepository.observeContacts); when
 * writing a new message, the chosen network's directory is searched too (ChatRepository.searchPeople).
 */
class ContactsViewModel(
    private val chatRepository: ChatRepository,
    connectionMonitor: ConnectionMonitor,
    isNewMessage: Boolean
) : ViewModel() {

    private val ui = MutableStateFlow(ContactsState(isNewMessage = isNewMessage))
    private val search = MutableStateFlow(SearchResult())
    private val events = Channel<ContactsEvent>()
    val eventFlow = events.receiveAsFlow()

    /** The last search: what was searched for, and the people found or why it failed. */
    private data class SearchResult(
        val network: ChatNetwork? = null,
        val query: String = "",
        val found: List<ChatContact> = emptyList(),
        val isSearching: Boolean = false,
        val error: UiText? = null
    )

    val state = combine(
        ui,
        chatRepository.observeContacts(),
        search,
        connectionMonitor.linkedNetworks
    ) { s, all, searched, linked ->
        // A network just connected has no contacts yet, but it can still be written to
        val networks = (all.map { it.network } + linked).distinct().sortedBy { it.ordinal }
        // With one network there's nothing to choose
        val network = s.network ?: networks.singleOrNull()?.takeIf { s.isNewMessage }
        val query = s.query.trim()
        val shown = all
            .filter { network == null || it.network == network }
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        val chosen = s.copy(network = network)
        val current = searched.takeIf { it.network == network && it.query == query && s.isNewMessage }
        val shownIds = shown.map { it.id }.toSet()
        chosen.copy(
            networks = networks,
            contacts = if (chosen.isPickingNetwork) emptyList() else shown,
            found = current?.found.orEmpty().filter { it.id !in shownIds },
            isSearching = current?.isSearching == true,
            searchError = current?.error,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ui.value)

    init {
        // Searching the network as the name is typed, once it's long enough and typing pauses
        viewModelScope.launch {
            combine(ui, state) { s, current -> current.network.takeIf { s.isNewMessage } to s.query.trim() }
                .distinctUntilChanged()
                .collectLatest { (network, query) ->
                    if (network == null || query.length < MIN_SEARCH_LENGTH) {
                        search.value = SearchResult()
                        return@collectLatest
                    }
                    delay(SEARCH_DELAY_MILLIS)
                    search.value = SearchResult(network, query, isSearching = true)
                    search.value = when (val result = chatRepository.searchPeople(network, query)) {
                        is Result.Success -> SearchResult(network, query, found = result.data)
                        // A network that can't be searched from here just isn't
                        is Result.Failure -> SearchResult(
                            network,
                            query,
                            error = UiText.Resource(Res.string.contacts_search_failed, arrayOf(network.displayName))
                                .takeIf { result.error != DataError.Remote.BAD_REQUEST }
                        )
                    }
                }
        }
    }

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

    suspend fun resolveMedia(url: String, thumbnail: Boolean): MediaRequest? =
        chatRepository.resolveMedia(url, thumbnail)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MIN_SEARCH_LENGTH = 2
        const val SEARCH_DELAY_MILLIS = 400L
    }
}
