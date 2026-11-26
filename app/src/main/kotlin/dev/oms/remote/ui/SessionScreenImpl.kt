package dev.oms.remote.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.oms.remote.protocol.api.AgentCommand
import dev.oms.remote.protocol.api.ConnectionStatus
import dev.oms.remote.protocol.api.PendingRequest
import dev.oms.remote.protocol.api.Phase
import dev.oms.remote.protocol.api.RemoteActions
import dev.oms.remote.protocol.api.RequestKind
import dev.oms.remote.protocol.api.SessionHeaderInfo
import dev.oms.remote.protocol.api.SessionUiState
import dev.oms.remote.protocol.api.Toast
import dev.oms.remote.protocol.api.ToastLevel
import dev.oms.remote.protocol.api.TranscriptItem
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Bare commands that open the model/thinking picker on the computer; the app opens its own sheet instead. */
private val PickerCommands = setOf("/model", "/models", "/switch", "/effort")

/** Characters of the prompt the rewind confirmation quotes. */
private const val RewindPreviewChars = 160

/** Pause after the last keystroke before asking the host for argument completions. */
private const val CompletionDebounceMs = 150L

/** How long a just-answered ask step stays on screen while the host sends the next one. */
private const val FollowUpHoldMs = 1_200L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionScreenImpl(
    state: SessionUiState,
    actions: RemoteActions,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onRescan: () -> Unit,
) {
    val header = rememberEqual(state.header)
    val connection = rememberEqual(state.connection)
    val pending = rememberEqual(state.pending)
    val agents = rememberEqual(state.agents)
    val phase = connection.phase
    val live = phase == Phase.Live
    val writable = live && !state.readOnly
    val isStreaming = header?.isStreaming == true

    val snackbarHostState = remember { SnackbarHostState() }
    ToastEffect(state.toasts, snackbarHostState, actions)

    var showAgents by rememberSaveable { mutableStateOf(false) }
    var showCommands by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    // The prompt whose "Rewind to here" waits for confirmation.
    var confirmRewind by remember { mutableStateOf<TranscriptItem.User?>(null) }
    var draftValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val draft = draftValue.text
    val setDraft: (String) -> Unit = { draftValue = TextFieldValue(it, selection = TextRange(it.length)) }
    val controls = state.controls
    val commands = rememberEqual(state.commands)
    val isCommandDraft = controls.commands && draft.trimStart().startsWith("/")
    // The host completes a command's argument (`/switch @sm` → model selectors) from its own completers.
    LaunchedEffect(draft, isCommandDraft) {
        if (!isCommandDraft) return@LaunchedEffect
        delay(CompletionDebounceMs)
        actions.complete(draft)
    }
    val completions = state.completions
    val suggestions = remember(draft, commands, controls.commands, completions) {
        val hostCompletions = completions?.takeIf { it.text == draft }?.items.orEmpty()
        when {
            !controls.commands -> emptyList()
            hostCompletions.isNotEmpty() -> argumentSuggestions(draft, hostCompletions)
            commands != null -> slashSuggestions(draft, commands)
            else -> emptyList()
        }
    }
    // A finished rewind hands back the prompt it went past, to edit and send again.
    val rewound = state.rewoundPrompt
    LaunchedEffect(rewound?.id) {
        if (rewound == null) return@LaunchedEffect
        setDraft(rewound.text)
        actions.consumeRewoundPrompt(rewound.id)
    }
    val latestRun = state.commandRuns.lastOrNull()
    val runCommand: (String) -> Unit = { text ->
        if (controls.modelPicker && text.trim() in PickerCommands) {
            // On the computer these open a picker; here the native sheet is that picker.
            showModels = true
            setDraft("")
        } else {
            actions.runCommand(text)
            setDraft("")
        }
    }
    // reqId of the request the user chose to put aside to type a message instead.
    var composeForReq by rememberSaveable { mutableStateOf<Long?>(null) }

    // A just-answered ask step, shown disabled until the host's follow-up request arrives.
    var answered by remember { mutableStateOf<PendingRequest?>(null) }
    var answeredUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(answeredUntil) {
        val wait = answeredUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        answered = null
    }

    val respond: (PendingRequest, String?) -> Unit = { request, value ->
        actions.respond(request.reqId, value)
        val expectsFollowUp = !request.isApproval && value != PendingRequest.CHAT &&
            !(request.kind == RequestKind.Select && value == null)
        if (expectsFollowUp) {
            answered = optimisticToggle(request, value)
            answeredUntil = System.currentTimeMillis() + FollowUpHoldMs
        } else {
            answered = null
        }
    }

    val firstRequest = pending.firstOrNull()
    val ghost = if (firstRequest == null) answered else null
    val panelRequest = firstRequest ?: ghost
    val composing = firstRequest != null && composeForReq == firstRequest.reqId

    val runningSubagents = agents.count { it.kind == "sub" && it.status == "running" }
    val lastItem = state.items.lastOrNull()
    val showWorking = isStreaming && !(lastItem is TranscriptItem.Assistant && lastItem.streaming)

    Scaffold(
        topBar = {
            SessionTopBar(
                header = header,
                hostLabel = state.hostLabel,
                connection = connection,
                readOnly = state.readOnly,
                runningSubagents = runningSubagents,
                agentCount = agents.size,
                onBack = onBack,
                onAgents = { showAgents = true },
                onDisconnect = onDisconnect,
                onModels = if (writable) ({ showModels = true }) else null,
                onCommands = if (writable && controls.commands) ({ showCommands = true }) else null,
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
        ) {
            ConnectionBanner(connection, onRetry = actions::reconnectNow, onRescan = onRescan)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val panelMax = maxHeight * 0.62f
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Transcript(
                            items = state.items,
                            hasOlder = state.hasOlder,
                            loadingOlder = state.loadingOlder,
                            showWorking = showWorking,
                            aborting = header?.isAborting == true,
                            phase = phase,
                            onLoadOlder = actions::loadOlder,
                            modifier = Modifier.fillMaxSize(),
                            onRewind = if (writable && controls.rewind) ({ confirmRewind = it }) else null,
                        )
                        ToastHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
                    }
                    when {
                        state.readOnly -> ReadOnlyFooter()
                        panelRequest != null && !composing -> PendingPanel(
                            request = panelRequest,
                            total = pending.size.coerceAtLeast(1),
                            enabled = live,
                            waiting = ghost != null,
                            isStreaming = isStreaming,
                            onRespond = respond,
                            onStop = actions::abort,
                            onWriteInstead = { composeForReq = firstRequest?.reqId },
                            modifier = Modifier.heightIn(max = panelMax),
                        )
                        else -> Composer(
                            value = draftValue,
                            onValueChange = { draftValue = it },
                            phase = phase,
                            canSend = writable,
                            isStreaming = isStreaming,
                            isAborting = header?.isAborting == true,
                            queuedCount = header?.queuedCount ?: 0,
                            pendingNotice = when {
                                firstRequest == null -> null
                                firstRequest.isApproval -> PendingNotice.Approval
                                else -> PendingNotice.Question
                            },
                            onBackToRequest = { composeForReq = null },
                            onSend = {
                                val text = draft.trim()
                                when {
                                    text.isEmpty() -> Unit
                                    isCommandDraft -> runCommand(text)
                                    else -> {
                                        actions.sendPrompt(text)
                                        setDraft("")
                                    }
                                }
                            },
                            onStop = actions::abort,
                            suggestions = suggestions,
                            onPickSuggestion = { setDraft(it.insert) },
                            commandsUnsupported = live && !controls.commands && draft.trimStart().startsWith("/"),
                            onOpenCommands = if (controls.commands) ({ showCommands = true }) else null,
                            isCommand = isCommandDraft,
                            run = latestRun,
                            onDismissRun = { latestRun?.let { actions.dismissCommandRun(it.id) } },
                            onCancelRun = { latestRun?.let { actions.cancelCommandRun(it.id) } },
                        )
                    }
                }
            }
        }
    }

    if (showCommands) {
        CommandPaletteSheet(
            commands = commands,
            onDismiss = { showCommands = false },
            onPick = { command ->
                showCommands = false
                when {
                    controls.modelPicker && "/${command.name}" in PickerCommands -> showModels = true
                    command.takesArgs -> setDraft("/${command.name} ")
                    else -> runCommand("/${command.name}")
                }
            },
        )
    }

    if (showModels) {
        ModelSheet(
            supported = controls.modelPicker,
            commandsAvailable = controls.commands,
            catalog = state.models,
            loading = state.loadingModels,
            currentModelName = header?.modelName ?: header?.modelId,
            currentThinking = header?.thinkingLevel,
            onLoad = actions::loadModels,
            onDismiss = { showModels = false },
            onPickModel = { model ->
                showModels = false
                actions.setModel(model.provider, model.id)
            },
            onPickThinking = actions::setThinking,
            onSearch = actions::searchModels,
        )
    }

    confirmRewind?.let { prompt ->
        RewindDialog(
            prompt = prompt,
            busy = isStreaming,
            onConfirm = {
                confirmRewind = null
                actions.rewind(prompt.key)
            },
            onDismiss = { confirmRewind = null },
        )
    }

    if (showAgents) {
        AgentsSheet(
            agents = agents,
            controlsEnabled = writable,
            onDismiss = { showAgents = false },
            onCommand = { id: String, command: AgentCommand, text: String? -> actions.agentCommand(id, command, text) },
        )
    }
}

/** Confirmation for "Rewind to here": the computer's session goes back to before [prompt]. */
@Composable
private fun RewindDialog(prompt: TranscriptItem.User, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rewind to here?") },
        text = {
            Text(
                if (busy) {
                    "The agent is still working. Stop it first, then rewind."
                } else {
                    "The session on the computer goes back to before this prompt. The later turns stay there as a " +
                        "branch, and the prompt comes back in your composer to edit and send again.\n\n" +
                        "\u201c${prompt.text.take(RewindPreviewChars)}${if (prompt.text.length > RewindPreviewChars) "\u2026" else ""}\u201d"
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text("Rewind") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** For a multi-select toggle, flip the option locally so the waiting panel already shows the new check. */
private fun optimisticToggle(request: PendingRequest, value: String?): PendingRequest {
    if (request.selectionMarker != "checkbox" || value == null) return request
    val index = request.options.indexOfFirst { it.label == value }
    if (index < 0 || index >= request.markableCount) return request
    val checked = if (index in request.checkedIndices) request.checkedIndices - index else request.checkedIndices + index
    return request.copy(checkedIndices = checked)
}

// ---------------------------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionTopBar(
    header: SessionHeaderInfo?,
    hostLabel: String,
    connection: ConnectionStatus,
    readOnly: Boolean,
    runningSubagents: Int,
    agentCount: Int,
    onBack: () -> Unit,
    onAgents: () -> Unit,
    onDisconnect: () -> Unit,
    onModels: (() -> Unit)?,
    onCommands: (() -> Unit)?,
) {
    TopAppBar(
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionDot(connection.phase)
                    Box(Modifier.width(8.dp))
                    Text(
                        text = header?.title ?: hostLabel,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HeaderSubtitle(
                    header,
                    hostLabel,
                    connection.phase,
                    modifier = if (onModels != null && header != null) Modifier.clickable(onClick = onModels) else Modifier,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            if (readOnly) ReadOnlyBadge()
            if (runningSubagents > 0) RunningAgentsChip(runningSubagents, onAgents)
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (onModels != null) {
                        DropdownMenuItem(
                            text = { Text("Model & thinking") },
                            leadingIcon = { Icon(Icons.Outlined.Psychology, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onModels()
                            },
                        )
                    }
                    if (onCommands != null) {
                        DropdownMenuItem(
                            text = { Text("Commands") },
                            leadingIcon = { Text("/", style = OmsStyle.mono, fontWeight = FontWeight.Bold) },
                            onClick = {
                                menuOpen = false
                                onCommands()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (agentCount > 0) "Agents ($agentCount)" else "Agents") },
                        leadingIcon = { Icon(Icons.Outlined.AccountTree, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onAgents()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Disconnect") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDisconnect()
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun HeaderSubtitle(header: SessionHeaderInfo?, hostLabel: String, phase: Phase, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val text = when {
            header == null -> if (phase == Phase.Connecting) "Connecting…" else hostLabel
            else -> listOfNotNull(
                header.cwd?.takeIf { it.isNotBlank() }?.let { shortenPath(it, maxLen = 24) },
                header.modelName ?: header.modelId,
            ).joinToString(" · ").ifEmpty { hostLabel }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val level = header?.thinkingLevel
        if (!level.isNullOrBlank()) ThinkingLevelChip(level)
        val percent = header?.contextPercent
        if (percent != null) ContextMeter(percent)
    }
}

@Composable
private fun ThinkingLevelChip(level: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.semantics { contentDescription = "Thinking level $level" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(11.dp))
            Text(level, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun ContextMeter(percent: Double) {
    val scheme = MaterialTheme.colorScheme
    val color = when {
        percent >= 90 -> scheme.error
        percent >= 75 -> OmsStyle.status.warn
        else -> scheme.primary
    }
    val rounded = percent.roundToInt().coerceIn(0, 100)
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Context $rounded percent used" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        CircularProgressIndicator(
            progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.size(11.dp),
            color = color,
            strokeWidth = 2.dp,
            trackColor = scheme.surfaceContainerHighest,
            gapSize = 0.dp,
        )
        Text("$rounded%", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectionDot(phase: Phase) {
    val status = OmsStyle.status
    val color = when (phase) {
        Phase.Live -> status.live
        Phase.Connecting, Phase.Reconnecting -> status.warn
        Phase.Ended, Phase.Failed -> MaterialTheme.colorScheme.error
    }
    val label = when (phase) {
        Phase.Live -> "Connected"
        Phase.Connecting -> "Connecting"
        Phase.Reconnecting -> "Reconnecting"
        Phase.Ended -> "Session ended"
        Phase.Failed -> "Connection failed"
    }
    val dot = Modifier
        .semantics { contentDescription = label }
        .size(9.dp)
    if (phase == Phase.Connecting || phase == Phase.Reconnecting) {
        // Only animate while pulsing so a live session schedules no frames.
        val transition = rememberInfiniteTransition(label = "dot")
        val alpha = transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "dot-alpha",
        )
        Box(dot.graphicsLayer { this.alpha = alpha.value }.background(color, CircleShape))
    } else {
        Box(dot.background(color, CircleShape))
    }
}

@Composable
private fun ReadOnlyBadge() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
            Text("View only", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RunningAgentsChip(count: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .padding(end = 2.dp)
            .semantics { contentDescription = "$count subagents running. Show agents" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Outlined.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("$count", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Connection banner
// ---------------------------------------------------------------------------------------------

@Composable
private fun rememberSecondsUntil(targetMs: Long?): Long? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(targetMs) {
        while (targetMs != null) {
            now = System.currentTimeMillis()
            if (now >= targetMs) break
            delay(250)
        }
    }
    return targetMs?.let { ((it - now).coerceAtLeast(0) + 999) / 1000 }
}

@Composable
private fun ConnectionBanner(connection: ConnectionStatus, onRetry: () -> Unit, onRescan: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val status = OmsStyle.status
    Box(Modifier.fillMaxWidth().animateContentSize()) {
        when (connection.phase) {
            Phase.Live -> Unit
            Phase.Connecting -> LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            Phase.Reconnecting -> {
                val seconds = rememberSecondsUntil(connection.nextRetryAtMs)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = status.warnContainer,
                    contentColor = status.onWarnContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = buildString {
                                append("Reconnecting")
                                if (seconds != null && seconds > 0) append(" in ${seconds}s") else append("…")
                                if (connection.attempt > 0) append(" · attempt ${connection.attempt}")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onRetry,
                            colors = ButtonDefaults.textButtonColors(contentColor = status.onWarnContainer),
                        ) { Text("Retry now") }
                    }
                }
            }
            Phase.Ended, Phase.Failed -> Surface(
                modifier = Modifier.fillMaxWidth(),
                color = scheme.errorContainer,
                contentColor = scheme.onErrorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = if (connection.phase == Phase.Ended) Icons.Outlined.LinkOff else Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (connection.phase == Phase.Ended) "Session ended" else "Connection failed",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val message = connection.message
                        if (!message.isNullOrBlank()) {
                            Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(
                        onClick = onRescan,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.error,
                            contentColor = scheme.onError,
                        ),
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Box(Modifier.width(6.dp))
                        Text("Scan new link")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Toasts
// ---------------------------------------------------------------------------------------------

private class ToastVisuals(override val message: String, val level: ToastLevel) : SnackbarVisuals {
    override val actionLabel: String? get() = null
    override val withDismissAction: Boolean get() = true
    override val duration: SnackbarDuration
        get() = if (level == ToastLevel.Error) SnackbarDuration.Long else SnackbarDuration.Short
}

/** Shows the newest toast; older ones are dropped. Each toast is dismissed once shown or superseded. */
@Composable
private fun ToastEffect(toasts: List<Toast>, host: SnackbarHostState, actions: RemoteActions) {
    val newest = toasts.lastOrNull()
    LaunchedEffect(newest?.id) {
        if (newest == null) return@LaunchedEffect
        toasts.forEach { if (it.id != newest.id) actions.dismissToast(it.id) }
        try {
            host.showSnackbar(ToastVisuals(newest.text, newest.level))
        } finally {
            actions.dismissToast(newest.id)
        }
    }
}

@Composable
private fun ToastHost(host: SnackbarHostState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val status = OmsStyle.status
    SnackbarHost(host, modifier) { data ->
        val level = (data.visuals as? ToastVisuals)?.level ?: ToastLevel.Info
        val container = when (level) {
            ToastLevel.Error -> scheme.errorContainer
            ToastLevel.Warning -> status.warnContainer
            ToastLevel.Info -> scheme.inverseSurface
        }
        val content = when (level) {
            ToastLevel.Error -> scheme.onErrorContainer
            ToastLevel.Warning -> status.onWarnContainer
            ToastLevel.Info -> scheme.inverseOnSurface
        }
        Snackbar(
            snackbarData = data,
            containerColor = container,
            contentColor = content,
            dismissActionContentColor = content,
        )
    }
}
