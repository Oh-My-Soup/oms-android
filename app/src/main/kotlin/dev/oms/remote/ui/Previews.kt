package dev.oms.remote.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.oms.remote.protocol.api.ConnectionStatus
import dev.oms.remote.protocol.api.Phase

private val PreviewHosts = listOf(
    HostEntry(
        id = "h1",
        name = "Work laptop",
        relayHost = "laptop.tail1234.ts.net",
        canWrite = true,
        lastConnectedMs = System.currentTimeMillis() - 5 * 60_000,
    ),
    HostEntry(
        id = "h2",
        name = "Build box",
        relayHost = "relay.example.dev",
        canWrite = false,
        lastConnectedMs = System.currentTimeMillis() - 3 * 24 * 3_600_000L,
    ),
    HostEntry(
        id = "h3",
        name = "Home desktop",
        relayHost = "192.168.1.20:7466",
        canWrite = true,
        lastConnectedMs = null,
    ),
)

@Preview(name = "Session · demo", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun SessionScreenPreview() {
    OmsTheme {
        SessionScreen(demoUiState(), NoopActions, onBack = {}, onDisconnect = {}, onRescan = {})
    }
}

@Preview(
    name = "Session · demo · dark",
    showBackground = true,
    widthDp = 393,
    heightDp = 852,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SessionScreenDarkPreview() {
    OmsTheme {
        SessionScreen(demoUiState().copy(pending = emptyList()), NoopActions, onBack = {}, onDisconnect = {}, onRescan = {})
    }
}

@Preview(name = "Session · reconnecting", showBackground = true, widthDp = 393, heightDp = 600)
@Composable
private fun SessionReconnectingPreview() {
    val state = demoUiState().copy(
        connection = ConnectionStatus(
            phase = Phase.Reconnecting,
            attempt = 3,
            nextRetryAtMs = System.currentTimeMillis() + 4_000,
        ),
        pending = emptyList(),
        toasts = emptyList(),
    )
    OmsTheme { SessionScreen(state, NoopActions, onBack = {}, onDisconnect = {}, onRescan = {}) }
}

@Preview(name = "Session · ended, view only", showBackground = true, widthDp = 393, heightDp = 600)
@Composable
private fun SessionEndedPreview() {
    val state = demoUiState().copy(
        connection = ConnectionStatus(phase = Phase.Ended, message = "The host closed the room"),
        readOnly = true,
        pending = emptyList(),
        toasts = emptyList(),
    )
    OmsTheme { SessionScreen(state, NoopActions, onBack = {}, onDisconnect = {}, onRescan = {}) }
}

@Preview(name = "Home · empty", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun HomeEmptyPreview() {
    OmsTheme {
        HomeScreen(
            hosts = emptyList(),
            activeHostId = null,
            linkError = null,
            notificationsBlocked = true,
            onOpenHost = {},
            onScanQr = {},
            onPasteLink = { _, _ -> },
            onRenameHost = { _, _ -> },
            onDeleteHost = {},
            onRequestNotifications = {},
            onOpenDemo = {},
        )
    }
}

@Preview(name = "Home · hosts", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun HomeHostsPreview() {
    OmsTheme {
        HomeScreen(
            hosts = PreviewHosts,
            activeHostId = "h1",
            linkError = "That doesn't look like a collab link.",
            notificationsBlocked = false,
            onOpenHost = {},
            onScanQr = {},
            onPasteLink = { _, _ -> },
            onRenameHost = { _, _ -> },
            onDeleteHost = {},
            onRequestNotifications = {},
            onOpenDemo = {},
        )
    }
}

@Preview(name = "Home · hosts · dark", showBackground = true, widthDp = 393, heightDp = 700, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeHostsDarkPreview() {
    OmsTheme {
        HomeScreen(
            hosts = PreviewHosts,
            activeHostId = null,
            linkError = null,
            notificationsBlocked = false,
            onOpenHost = {},
            onScanQr = {},
            onPasteLink = { _, _ -> },
            onRenameHost = { _, _ -> },
            onDeleteHost = {},
            onRequestNotifications = {},
            onOpenDemo = {},
        )
    }
}

@Composable
private fun PanelFrame(content: @Composable () -> Unit) {
    OmsTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Preview(name = "Panel · approval", showBackground = true, widthDp = 393)
@Composable
private fun ApprovalPanelPreview() {
    PanelFrame {
        PendingPanel(
            request = sampleApprovalRequest(),
            total = 2,
            enabled = true,
            waiting = false,
            isStreaming = true,
            onRespond = { _, _ -> },
            onStop = {},
            onWriteInstead = {},
        )
    }
}

@Preview(name = "Panel · ask multi-select", showBackground = true, widthDp = 393)
@Composable
private fun AskMultiSelectPreview() {
    PanelFrame {
        PendingPanel(
            request = sampleAskMultiSelect(),
            total = 1,
            enabled = true,
            waiting = false,
            isStreaming = true,
            onRespond = { _, _ -> },
            onStop = {},
            onWriteInstead = {},
        )
    }
}

@Preview(name = "Panel · ask single-select · dark", showBackground = true, widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AskSingleSelectPreview() {
    PanelFrame {
        PendingPanel(
            request = sampleAskSingleSelect(),
            total = 1,
            enabled = true,
            waiting = false,
            isStreaming = false,
            onRespond = { _, _ -> },
            onStop = {},
            onWriteInstead = {},
        )
    }
}

@Preview(name = "Panel · editor", showBackground = true, widthDp = 393)
@Composable
private fun EditorPanelPreview() {
    PanelFrame {
        PendingPanel(
            request = sampleEditorRequest(),
            total = 1,
            enabled = true,
            waiting = false,
            isStreaming = false,
            onRespond = { _, _ -> },
            onStop = {},
            onWriteInstead = {},
        )
    }
}

@Preview(name = "Agents sheet", showBackground = true, widthDp = 393, heightDp = 700)
@Composable
private fun AgentsSheetPreview() {
    PanelFrame {
        AgentsList(demoUiState().agents, controlsEnabled = true, onCommand = { _, _, _ -> })
    }
}
