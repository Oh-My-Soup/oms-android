package dev.oms.remote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreenImpl(
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
    var renameTarget by remember { mutableStateOf<HostEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<HostEntry?>(null) }
    var pasteOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(linkError) { if (linkError != null) pasteOpen = true }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("oms remote") },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScanQr,
                icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
                text = { Text("Scan QR") },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (notificationsBlocked) {
                item(key = "notifications") { NotificationsBanner(onRequestNotifications) }
            }
            if (hosts.isEmpty()) {
                item(key = "empty") { EmptyHosts() }
            } else {
                item(key = "hosts-title") {
                    Text(
                        text = "Saved hosts",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                items(hosts, key = { it.id }) { host ->
                    HostCard(
                        host = host,
                        active = host.id == activeHostId,
                        onOpen = { onOpenHost(host) },
                        onRename = { renameTarget = host },
                        onDelete = { deleteTarget = host },
                    )
                }
            }
            item(key = "paste") {
                PasteLinkSection(
                    expanded = pasteOpen,
                    onToggle = { pasteOpen = !pasteOpen },
                    linkError = linkError,
                    onPasteLink = onPasteLink,
                )
            }
            item(key = "demo") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onOpenDemo) { Text("Demo") }
                }
            }
        }
    }

    renameTarget?.let { host ->
        RenameDialog(
            host = host,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                onRenameHost(host, name)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { host ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("Delete “${host.name}”?") },
            text = { Text("The saved link is removed from this phone. To reconnect you'll need to scan the QR code again.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteHost(host)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(
    host: HostEntry,
    active: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (active) scheme.primaryContainer else scheme.surfaceContainerLow,
        contentColor = if (active) scheme.onPrimaryContainer else scheme.onSurface,
        border = if (active) BorderStroke(1.5.dp, scheme.primary) else BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = if (active) "Open session" else "Connect",
                    onLongClickLabel = "Host options",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                    onClick = onOpen,
                )
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(if (active) scheme.primary else scheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Computer,
                    contentDescription = null,
                    tint = if (active) scheme.onPrimary else scheme.onSecondaryContainer,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = host.relayHost,
                    style = OmsStyle.monoSmall,
                    color = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AccessBadge(host.canWrite)
                    Text(
                        text = host.lastConnectedMs?.let { relativeTime(it) } ?: "never connected",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (active) {
                Surface(shape = CircleShape, color = scheme.primary, contentColor = scheme.onPrimary) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(Modifier.size(7.dp).background(OmsStyle.status.live, CircleShape))
                        Text("Open", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${host.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = scheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = scheme.error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessBadge(canWrite: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (canWrite) scheme.tertiaryContainer else scheme.surfaceContainerHighest,
        contentColor = if (canWrite) scheme.onTertiaryContainer else scheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = if (canWrite) Icons.Outlined.Tune else Icons.Outlined.Visibility,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Text(if (canWrite) "control" else "view only", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NotificationsBanner(onRequestNotifications: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.tertiaryContainer,
        contentColor = scheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Outlined.NotificationsOff, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Notifications are off", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Allow them to hear when the agent needs your approval or finishes a turn.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onRequestNotifications,
                    modifier = Modifier.padding(top = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.tertiary,
                        contentColor = scheme.onTertiary,
                    ),
                ) { Text("Allow notifications") }
            }
        }
    }
}

@Composable
private fun EmptyHosts() {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerLow) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(64.dp).background(scheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = scheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text("Connect to oms", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Follow the transcript, send prompts and answer approvals from your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val code = SpanStyle(fontFamily = FontFamily.Monospace, background = scheme.surfaceContainerHighest)
                Step(1) {
                    Text(
                        text = buildAnnotatedString {
                            append("On your computer, run ")
                            withStyle(code) { append(" /collab ") }
                            append(" in oms.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Step(2) {
                    Text(
                        text = "Tap Scan QR and point the camera at the QR code it prints.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Step(3) {
                    Text(
                        text = "Works anywhere your phone can reach the relay, including over Tailscale. " +
                            "The relay only forwards encrypted traffic.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.size(24.dp).background(scheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = scheme.onSecondaryContainer)
        }
        Box(Modifier.weight(1f).padding(top = 2.dp)) { content() }
    }
}

@Composable
private fun PasteLinkSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    linkError: String?,
    onPasteLink: (link: String, name: String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "paste-chevron")
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (expanded) "Hide link field" else "Show link field", onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Outlined.Link, contentDescription = null, tint = scheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Paste a link", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Use the link /collab prints when you can't scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Collab link") },
                        placeholder = { Text("https://… or room.secret") },
                        isError = linkError != null,
                        supportingText = linkError?.let { error -> { Text(error) } },
                        textStyle = OmsStyle.mono,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val clip = clipboard.getClipEntry()?.clipData
                                        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text else null
                                        if (!text.isNullOrBlank()) link = text.toString().trim()
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste from clipboard")
                            }
                        },
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name (optional)") },
                        placeholder = { Text("Work laptop") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { onPasteLink(link.trim(), name.trim()) },
                            enabled = link.isNotBlank(),
                        ) {
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(host: HostEntry, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable(host.id) { mutableStateOf(host.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename host") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
