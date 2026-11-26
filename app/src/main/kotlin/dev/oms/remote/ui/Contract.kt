package dev.oms.remote.ui

import androidx.compose.runtime.Composable
import dev.oms.remote.protocol.api.RemoteActions
import dev.oms.remote.protocol.api.SessionUiState

/*
 * Screen entry points used by MainActivity. The UI work replaces these bodies but MUST keep
 * every signature below unchanged (the app shell calls them).
 */

/** A saved host as shown on the home screen. */
data class HostEntry(
    val id: String,
    val name: String,
    val relayHost: String,
    val canWrite: Boolean,
    val lastConnectedMs: Long?,
)

@Composable
fun OmsTheme(content: @Composable () -> Unit) {
    OmsThemeImpl(content)
}

/**
 * Saved hosts + pairing. [linkError] is shown under the paste field after [onPasteLink] fails
 * validation. [activeHostId] marks the host with a live session ("Open" instead of "Connect").
 */
@Composable
fun HomeScreen(
    hosts: List<HostEntry>,
    activeHostId: String?,
    linkError: String?,
    notificationsBlocked: Boolean,
    onOpenHost: (HostEntry) -> Unit,
    onScanQr: () -> Unit,
    onPasteLink: (link: String, name: String) -> Unit,
    onRenameHost: (HostEntry, String) -> Unit,
    onDeleteHost: (HostEntry) -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenDemo: () -> Unit,
) {
    HomeScreenImpl(
        hosts = hosts,
        activeHostId = activeHostId,
        linkError = linkError,
        notificationsBlocked = notificationsBlocked,
        onOpenHost = onOpenHost,
        onScanQr = onScanQr,
        onPasteLink = onPasteLink,
        onRenameHost = onRenameHost,
        onDeleteHost = onDeleteHost,
        onRequestNotifications = onRequestNotifications,
        onOpenDemo = onOpenDemo,
    )
}

/**
 * The live session: header, transcript, composer, pending dialogs, agents, toasts.
 * [onRescan] is offered when the room ended (`bye`) or failed, since a new link is needed.
 */
@Composable
fun SessionScreen(
    state: SessionUiState,
    actions: RemoteActions,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onRescan: () -> Unit,
) {
    SessionScreenImpl(state, actions, onBack, onDisconnect, onRescan)
}

/** Sample state for previews and the in-app demo (no relay needed). */
fun demoUiState(): SessionUiState = buildDemoUiState()

/** Actions that do nothing; used with [demoUiState]. */
object NoopActions : RemoteActions {
    override fun sendPrompt(text: String) {}
    override fun abort() {}
    override fun respond(reqId: Long, value: String?) {}
    override fun agentCommand(agentId: String, command: dev.oms.remote.protocol.api.AgentCommand, text: String?) {}
    override fun loadOlder() {}
    override fun dismissToast(id: Long) {}
    override fun reconnectNow() {}
}
