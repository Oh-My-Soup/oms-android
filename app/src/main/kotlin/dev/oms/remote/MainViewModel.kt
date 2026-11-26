package dev.oms.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.oms.remote.data.SavedHost
import dev.oms.remote.protocol.api.RemoteSession
import dev.oms.remote.ui.HostEntry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Navigation and pairing state for [MainActivity]. Survives configuration changes but not process
 * death, so a restarted app always lands on Home (v1 does not auto-reconnect).
 */
class MainViewModel : ViewModel() {
    enum class Screen { Home, Session, Demo }

    sealed interface Event {
        /** A connect succeeded; the activity may ask for the notification permission. */
        data object Connected : Event
    }

    private val _screen = MutableStateFlow(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _linkError = MutableStateFlow<String?>(null)
    val linkError: StateFlow<String?> = _linkError.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    val hosts: StateFlow<List<HostEntry>> = RemoteHub.hosts
        .map(::toEntries)
        .stateIn(viewModelScope, SharingStarted.Eagerly, toEntries(RemoteHub.hosts.value))

    val activeHostId: StateFlow<String?> = RemoteHub.activeHostId
    val session: StateFlow<RemoteSession?> = RemoteHub.session

    init {
        // Disconnected elsewhere (notification action, host deleted): leave the session screen.
        viewModelScope.launch {
            RemoteHub.session.collect { if (it == null && _screen.value == Screen.Session) _screen.value = Screen.Home }
        }
    }

    fun openHost(host: HostEntry) {
        if (host.id == activeHostId.value && session.value != null) {
            _screen.value = Screen.Session
        } else {
            connect(host.id)
        }
    }

    fun pasteLink(text: String, name: String) = addAndConnect(text, name)

    fun onScanned(text: String) = addAndConnect(text, "")

    fun onSharedText(text: String) = addAndConnect(text, "")

    /** Shows a pairing problem on Home. */
    fun showLinkError(message: String) {
        _linkError.value = message
        _screen.value = Screen.Home
    }

    fun rename(hostId: String, name: String) = RemoteHub.rename(hostId, name)

    fun delete(hostId: String) = RemoteHub.delete(hostId)

    /** From a notification: show the current session if there is one. */
    fun openSession() {
        if (session.value != null) _screen.value = Screen.Session
    }

    fun openDemo() {
        _screen.value = Screen.Demo
    }

    fun goHome() {
        _screen.value = Screen.Home
    }

    fun disconnect() {
        RemoteHub.disconnect()
        _screen.value = Screen.Home
    }

    private fun addAndConnect(text: String, name: String) {
        RemoteHub.addLink(text, name)
            .onSuccess { connect(it.id) }
            .onFailure { showLinkError(it.message ?: "Invalid collab link") }
    }

    private fun connect(hostId: String) {
        RemoteHub.connect(hostId)
            .onSuccess {
                _linkError.value = null
                _screen.value = Screen.Session
                _events.trySend(Event.Connected)
            }
            .onFailure { showLinkError(it.message ?: "Couldn't connect") }
    }

    private fun toEntries(hosts: List<SavedHost>): List<HostEntry> = hosts
        .sortedByDescending { it.lastConnectedMs ?: 0L }
        .map { HostEntry(it.id, it.name, it.relayHost, it.canWrite, it.lastConnectedMs) }
}
