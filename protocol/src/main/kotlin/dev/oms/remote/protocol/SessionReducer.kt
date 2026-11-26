package dev.oms.remote.protocol

import dev.oms.remote.protocol.api.AgentCommand
import dev.oms.remote.protocol.api.AgentInfo
import dev.oms.remote.protocol.api.CollabLink
import dev.oms.remote.protocol.api.CommandCompletions
import dev.oms.remote.protocol.api.CommandInfo
import dev.oms.remote.protocol.api.CommandRun
import dev.oms.remote.protocol.api.CommandRunStatus
import dev.oms.remote.protocol.api.CompletionItem
import dev.oms.remote.protocol.api.ConnectionStatus
import dev.oms.remote.protocol.api.HostControls
import dev.oms.remote.protocol.api.ModelCatalog
import dev.oms.remote.protocol.api.ModelInfo
import dev.oms.remote.protocol.api.PendingRequest
import dev.oms.remote.protocol.api.RewoundPrompt
import dev.oms.remote.protocol.api.RoleModel
import dev.oms.remote.protocol.api.SessionHeaderInfo
import dev.oms.remote.protocol.api.Phase
import dev.oms.remote.protocol.api.SessionSignal
import dev.oms.remote.protocol.api.SessionUiState
import dev.oms.remote.protocol.api.Toast
import dev.oms.remote.protocol.api.ToastLevel
import dev.oms.remote.protocol.api.TranscriptItem
import kotlinx.serialization.json.JsonObject

internal enum class SessionTimer { Welcome, Snapshot, History }

/** Side effects the reducer asks its owner to perform. All calls happen on the session's actor. */
internal interface SessionEffects {
    fun send(frame: JsonObject)

    fun signal(signal: SessionSignal)

    /** Arm [timer]; when it fires, call [SessionReducer.onTimer] with the same [token]. */
    fun schedule(timer: SessionTimer, token: Long, delayMs: Long)

    fun cancel(timer: SessionTimer)

    /** Close the socket for good: the session reached [Phase.Ended] or [Phase.Failed]. */
    fun shutdown()

    /** Drop the current socket and open a new one right away (no backoff): a protocol downgrade. */
    fun reconnect()
}

/**
 * Collab v5 guest state machine (spec 4-8). A session starts on v5 and switches for good to an older
 * supported version (v4, v3) when the host answers `hello` with `protocol mismatch: host speaks vN`.
 * Single-threaded: the owner feeds socket lifecycle, decoded host frames, timers and user actions in
 * order, then reads [uiState].
 */
internal class SessionReducer(
    private val link: CollabLink,
    private val deviceName: String,
    private val effects: SessionEffects,
    private val clock: () -> Long,
) {
    private class PendingSnapshot(val total: Long, val page: HistoryPage?) {
        /** Newest snapshot entries only: a v3 snapshot is the whole transcript. */
        val entries = ArrayDeque<JsonObject>()
        val ids = HashSet<String>()
        val live = ArrayList<JsonObject>()
        var received = 0L
        var dropped = false

        fun add(entry: JsonObject) {
            if (!ids.add(entry.entryId())) return
            entries.addLast(entry)
            if (entries.size > EntryWindow.LIVE_CAP) {
                entries.removeFirst()
                dropped = true
            }
        }
    }

    private class PendingHistory(val reqId: Long, val cursorId: String)

    private class StreamBaseline(val id: Long, val revision: Long, val message: JsonObject)

    // Connection
    private var phase = Phase.Connecting
    private var attempt = 0
    private var nextRetryAtMs: Long? = null
    private var statusMessage: String? = null
    private var welcomed = false
    private var everLive = false
    private var protocol = GuestFrames.PROTOCOL_VERSION
    private var readOnly = false

    // Session
    private var header: JsonObject? = null
    private var state: JsonObject? = null
    private var working = false
    private var lastStateStreaming: Boolean? = null
    private val window = EntryWindow()
    private var hasOlder = false
    private var snapshot: PendingSnapshot? = null
    private var history: PendingHistory? = null
    private var reqSeq = 0L

    // Stream
    private var baseline: StreamBaseline? = null
    private var ghost: StreamGhost? = null
    private var ghostSeq = 0L
    private val activeTools = LinkedHashMap<String, ActiveTool>()

    // Requests
    private val pending = ArrayList<PendingRequest>()
    private val seenReqIds = LinkedHashSet<Long>()
    private var awaitingReplay: MutableSet<Long>? = null

    // Agents
    private var agents: List<JsonObject> = emptyList()
    private val progress = LinkedHashMap<String, JsonObject>()
    private val lifecycle = LinkedHashMap<String, JsonObject>()
    private var unlistedBusIds: Set<String> = emptySet()

    // Host controls: commands (v5), the model catalog (welcome.features), the guest's own requests
    private var features: Set<String> = emptySet()
    private var hostCommands = false
    private var commands: List<CommandInfo>? = null
    private var completions: CommandCompletions? = null
    private var completionsReq: PendingCompletion? = null
    private var models: ModelCatalog? = null
    private var modelsReq: Long? = null
    private val runs = ArrayDeque<CommandRun>()
    private val runReqs = HashMap<Long, OpenRun>()
    private var runSeq = 0L
    private var rewoundPrompt: RewoundPrompt? = null
    private var rewoundSeq = 0L

    // The model & thinking sheet over `/switch` and `/effort`, for v5 hosts without the `models` feature
    private val pickerReqs = HashMap<Long, PickerQuery>()
    private var pickerModels: List<ModelInfo>? = null
    private var pickerRoles: List<RoleModel>? = null
    private var pickerLevels: List<String>? = null
    private var probedLevels: List<String>? = null
    private var modelSearch = ""

    /** `snapshot-chunk` frames received since the last welcome, acknowledged to v5 hosts (spec 4.4). */
    private var snapshotChunks = 0L

    private class OpenRun(val runId: Long, val refreshesModels: Boolean, val rewind: Boolean = false) {
        /** A rewind's latest output block: the host sends the rewound prompt verbatim as the last one. */
        var lastBlock: StringBuilder? = null
    }

    private class PendingCompletion(val reqId: Long, val text: String)

    /** What a picker request asked the host; its answer builds the sheet's catalog. */
    private sealed interface PickerQuery {
        /** `/switch <query>` completions: model selectors, best match first. */
        data class Models(val query: String) : PickerQuery

        /** `/switch @` completions: the configured roles and their models. */
        data object Roles : PickerQuery

        /** `/effort ` completions: every thinking selector oms knows. */
        data object Levels : PickerQuery

        /** A bare `/effort`, never shown as a run: its output lists the current model's levels. */
        class EffortProbe : PickerQuery {
            val output = StringBuilder()
        }
    }

    // Toasts
    private val toasts = ArrayDeque<Toast>()
    private var toastSeq = 0L

    // Timers
    private var timerSeq = 0L
    private val timerTokens = HashMap<SessionTimer, Long>()

    // Derived-state caches
    private val transcript = TranscriptBuilder()
    private var committed: TranscriptBuilder.Committed? = null
    private var items: List<TranscriptItem>? = null
    private var agentInfos: List<AgentInfo>? = null

    val isTerminal: Boolean get() = phase == Phase.Ended || phase == Phase.Failed

    /** True once a welcome and its snapshot completed at least once. */
    val hasJoined: Boolean get() = everLive

    private val canMutate: Boolean get() = phase == Phase.Live && welcomed && link.canWrite && !readOnly

    // ── Connection lifecycle ────────────────────────────────────────────────────────────────

    fun start() {
        arm(SessionTimer.Welcome, WELCOME_TIMEOUT_MS)
    }

    /** A new socket is being opened (first connect or retry). */
    fun onConnecting() {
        nextRetryAtMs = null
    }

    fun onSocketOpen() {
        if (isTerminal) return
        dropConnectionState()
        nextRetryAtMs = null
        effects.send(GuestFrames.hello(deviceName, link.writeToken, protocol))
    }

    /** The socket dropped and a retry will follow (spec 2.4/2.6). */
    fun onSocketLost(reason: String) {
        if (isTerminal) return
        dropConnectionState()
        phase = Phase.Reconnecting
        statusMessage = reason
    }

    fun onRetryScheduled(attempt: Int, atMs: Long) {
        if (isTerminal) return
        this.attempt = attempt
        nextRetryAtMs = atMs
    }

    fun fail(message: String) {
        if (terminate(Phase.Failed, message)) effects.signal(SessionSignal.Failed(message))
    }

    fun end(reason: String) {
        if (terminate(Phase.Ended, reason)) effects.signal(SessionSignal.Ended(reason))
    }

    fun onTimer(timer: SessionTimer, token: Long) {
        if (timerTokens[timer] != token) return
        timerTokens.remove(timer)
        when (timer) {
            SessionTimer.Welcome -> if (!everLive && !welcomed) fail(ERR_WELCOME_TIMEOUT)
            SessionTimer.Snapshot -> if (snapshot != null) fail(ERR_SNAPSHOT_TIMEOUT)
            SessionTimer.History -> if (history != null) {
                history = null
                toast(ToastLevel.Warning, "Loading older messages timed out. Try again.")
            }
        }
    }

    /** Socket-scoped state that a new connection replaces (spec 2.6 step 2). */
    private fun dropConnectionState() {
        welcomed = false
        snapshot = null
        disarm(SessionTimer.Snapshot)
        clearHistoryRequest()
        resetStream()
        dropControlRequests()
    }

    /** Replies to requests sent on a lost socket never arrive; a command may still have run on the host. */
    private fun dropControlRequests() {
        completionsReq = null
        modelsReq = null
        pickerReqs.clear()
        if (runReqs.isEmpty()) return
        for (open in runReqs.values) {
            updateRun(open.runId) { it.copy(status = CommandRunStatus.Failed, error = ERR_RUN_LOST) }
        }
        runReqs.clear()
    }

    private fun terminate(to: Phase, message: String): Boolean {
        if (isTerminal) return false
        phase = to
        statusMessage = message
        attempt = 0
        nextRetryAtMs = null
        welcomed = false
        snapshot = null
        for (timer in SessionTimer.entries) disarm(timer)
        clearHistoryRequest()
        resetStream()
        dropControlRequests()
        activeTools.clear()
        pending.clear()
        awaitingReplay = null
        invalidateTranscript()
        effects.shutdown()
        return true
    }

    // ── Host frames ─────────────────────────────────────────────────────────────────────────

    fun onFrame(frame: HostFrame) {
        if (isTerminal) return
        settleReplay(frame)
        when (frame) {
            is HostFrame.Welcome -> onWelcome(frame)
            is HostFrame.SnapshotChunk -> {
                acknowledgeChunk()
                onSnapshotChunk(frame)
            }
            is HostFrame.Entry -> onEntry(frame.entry)
            is HostFrame.Event -> onEvent(frame.event)
            is HostFrame.Stream -> onStream(frame)
            is HostFrame.StreamDelta -> onStreamDelta(frame)
            is HostFrame.History -> onHistory(frame)
            is HostFrame.State -> onState(frame.state)
            is HostFrame.Bus -> onBus(frame)
            is HostFrame.Agents -> {
                agents = frame.agents
                pruneBusMaps()
                agentInfos = null
            }
            is HostFrame.UiRequest -> onUiRequest(frame.request)
            is HostFrame.UiRequestEnd -> onUiRequestEnd(frame.reqId)
            is HostFrame.Commands -> if (welcomed && hostCommands && canWriteLink) commands = frame.commands
            is HostFrame.CommandOutput -> onCommandOutput(frame)
            is HostFrame.CommandEnd -> onCommandEnd(frame)
            is HostFrame.Completions -> onCompletions(frame)
            is HostFrame.Models -> onModels(frame)
            is HostFrame.Bye -> end(frame.reason)
            is HostFrame.Error -> if (welcomed) toast(ToastLevel.Error, frame.message) else onJoinRefused(frame.message)
            is HostFrame.Transcript, is HostFrame.Unknown -> Unit
        }
    }

    /** [onFrame] threw on [frame]: snapshot and stream failures are fatal, others become a toast (collab-web). */
    fun onApplyError(frame: HostFrame) {
        when (frame) {
            is HostFrame.Welcome, is HostFrame.SnapshotChunk -> fail("Could not apply the host's session snapshot")
            is HostFrame.Stream, is HostFrame.StreamDelta -> fail(ERR_STREAM_PATCH)
            else -> toast(ToastLevel.Error, "Could not apply an update from the host")
        }
    }

    /** Paged history (and therefore [hasOlder]/[loadOlder]) only exists from v4. */
    private val pagedHistory: Boolean get() = GuestFrames.hasPagedHistory(protocol)

    /**
     * An `error` before `welcome` ends the join (spec 4.2), except a protocol mismatch from a host
     * speaking another supported version: switch to it and rejoin at once.
     */
    private fun onJoinRefused(message: String) {
        val hostVersion = PROTOCOL_MISMATCH.find(message)?.groupValues?.get(1)?.toIntOrNull()
        when {
            hostVersion == null || hostVersion == protocol -> fail(message)
            hostVersion in GuestFrames.SUPPORTED_VERSIONS -> {
                protocol = hostVersion
                dropConnectionState()
                effects.reconnect()
            }
            else -> fail(unsupportedVersion(hostVersion.toLong()))
        }
    }

    private fun unsupportedVersion(hostVersion: Long): String =
        if (hostVersion > GuestFrames.PROTOCOL_VERSION) {
            "This oms speaks collab v$hostVersion; update oms remote"
        } else {
            "This oms speaks collab v$hostVersion, which oms remote no longer supports; update oms"
        }

    private fun onWelcome(frame: HostFrame.Welcome) {
        if (frame.proto != null && frame.proto != protocol.toLong()) {
            fail(unsupportedVersion(frame.proto))
            return
        }
        disarm(SessionTimer.Welcome)
        welcomed = true
        header = frame.header
        readOnly = frame.readOnly
        features = frame.features
        hostCommands = GuestFrames.hasCommands(protocol)
        // A v5 host sends a full-control guest its command list right after the welcome; keep the
        // previous list until then so the composer does not flicker on a reconnect.
        if (!hostCommands || !canWriteLink) commands = null
        if (!hasFeature(GuestFrames.FEATURE_MODELS)) models = null
        resetPicker()
        completions = null
        snapshotChunks = 0
        agents = frame.agents
        progress.clear()
        lifecycle.clear()
        unlistedBusIds = emptySet()
        agentInfos = null
        resetStream()
        activeTools.clear()
        clearHistoryRequest()
        // The host replays pending dialogs after every welcome (spec 7.3): keep their ids so the
        // replay does not notify again and requests settled while away are reported resolved.
        if (pending.isNotEmpty()) {
            awaitingReplay = (awaitingReplay ?: HashSet()).apply { pending.forEach { add(it.reqId) } }
            pending.clear()
        }
        // A reconnect that finds the agent idle again still reports the turn that finished meanwhile.
        frame.state?.let(::onState) ?: setWorking(false)
        if (frame.entryCount <= 0) {
            hasOlder = frame.history?.beforeId != null
            window.replace(emptyList())
            snapshot = null
            goLive()
        } else {
            snapshot = PendingSnapshot(frame.entryCount, frame.history)
            arm(SessionTimer.Snapshot, SNAPSHOT_GAP_MS)
        }
        invalidateTranscript()
    }

    private fun onSnapshotChunk(frame: HostFrame.SnapshotChunk) {
        val pendingSnapshot = snapshot ?: return
        for (entry in frame.entries) {
            pendingSnapshot.add(entry)
        }
        pendingSnapshot.received += frame.entries.size
        if (!frame.final && pendingSnapshot.received < pendingSnapshot.total) {
            arm(SessionTimer.Snapshot, SNAPSHOT_GAP_MS)
            return
        }
        disarm(SessionTimer.Snapshot)
        val merged = pendingSnapshot.entries + pendingSnapshot.live.filter { it.entryId() !in pendingSnapshot.ids }
        val trimmed = window.replace(merged)
        hasOlder = pendingSnapshot.page?.beforeId != null || pendingSnapshot.dropped || trimmed
        snapshot = null
        goLive()
        invalidateTranscript()
    }

    private fun goLive() {
        phase = Phase.Live
        everLive = true
        attempt = 0
        nextRetryAtMs = null
        statusMessage = null
    }

    private fun onEntry(entry: JsonObject) {
        // The committed row supersedes the finished ghost, even while a snapshot is buffering.
        if (ghost?.done == true && entry.isAssistantMessageEntry()) {
            ghost = null
            invalidateTranscript()
        }
        if (!welcomed) return
        val pendingSnapshot = snapshot
        if (pendingSnapshot != null) {
            if (entry.entryId() !in pendingSnapshot.ids && pendingSnapshot.live.none { it.entryId() == entry.entryId() }) {
                pendingSnapshot.live += entry
            }
            return
        }
        val trimmed = window.append(entry) ?: return
        if (trimmed) hasOlder = true
        invalidateTranscript()
    }

    private fun onStream(frame: HostFrame.Stream) {
        if (!welcomed) return
        val current = ghost
        val continuing = current != null && !current.done && (baseline == null || baseline?.id == frame.id)
        baseline = StreamBaseline(frame.id, frame.revision, frame.message)
        ghost = StreamGhost(if (continuing) current.key else nextGhostKey(), frame.message, done = false)
        items = null
    }

    private fun onStreamDelta(frame: HostFrame.StreamDelta) {
        if (!welcomed) return
        val base = baseline
        if (base == null || base.id != frame.id || base.revision != frame.previousRevision || frame.revision <= frame.previousRevision) {
            fail(ERR_STREAM_DESYNC)
            return
        }
        val message = CollabStream.apply(base.message, frame.patch)
        if (message == null) {
            fail(ERR_STREAM_PATCH)
            return
        }
        baseline = StreamBaseline(frame.id, frame.revision, message)
        ghost = StreamGhost(ghost?.key ?: nextGhostKey(), message, done = false)
        items = null
    }

    private fun resetStream() {
        if (baseline == null && ghost == null) return
        baseline = null
        ghost = null
        items = null
    }

    private fun nextGhostKey(): String = "stream:${++ghostSeq}"

    private fun onEvent(event: JsonObject) {
        when (event.str("type")) {
            "message_update" -> {
                // v3 forwards assistant updates as events carrying the whole message so far.
                val message = event.obj("message") ?: return
                if (message.str("role") != "assistant") return
                val current = ghost
                baseline = null
                ghost = StreamGhost(current?.takeIf { !it.done }?.key ?: nextGhostKey(), message, done = false)
                items = null
            }
            "message_start", "message_end" -> {
                val message = event.obj("message") ?: return
                if (message.str("role") != "assistant") return
                val done = event.str("type") == "message_end"
                baseline = null
                ghost = StreamGhost(ghost?.key?.takeIf { done } ?: nextGhostKey(), message, done)
                items = null
            }
            "tool_execution_start" -> {
                val id = event.str("toolCallId") ?: return
                activeTools[id] = ActiveTool(id, event.str("toolName") ?: "tool", event["args"], event.str("intent"), null)
                invalidateTranscript()
            }
            "tool_execution_update" -> {
                val id = event.str("toolCallId") ?: return
                val partial = event["partialResult"]
                activeTools[id] = activeTools[id]?.copy(partialResult = partial)
                    ?: ActiveTool(id, event.str("toolName") ?: "tool", event["args"], null, partial)
                invalidateTranscript()
            }
            "tool_execution_end" -> {
                val id = event.str("toolCallId") ?: return
                if (activeTools.remove(id) != null) invalidateTranscript()
            }
            "agent_start" -> setWorking(true)
            "agent_end" -> setWorking(false)
            "notice" -> toast(level(event.str("level")), event.str("message") ?: return)
            "auto_retry_start" -> toast(
                ToastLevel.Warning,
                "retry ${event.long("attempt") ?: "?"}/${event.long("maxAttempts") ?: "?"}: ${event.str("errorMessage").orEmpty()}",
            )
            "auto_retry_end" -> if (event.bool("success") != true) {
                toast(ToastLevel.Error, event.str("finalError") ?: "retry failed")
            }
            "auto_compaction_start" -> toast(ToastLevel.Info, "compacting context (${event.str("reason") ?: "auto"})")
            "auto_compaction_end" -> if (event.bool("skipped") != true) {
                val error = event.str("errorMessage")
                when {
                    event.bool("aborted") == true -> toast(ToastLevel.Warning, "compaction aborted")
                    error != null -> toast(ToastLevel.Error, "compaction failed: $error")
                    else -> toast(ToastLevel.Info, "context compacted")
                }
            }
        }
    }

    private fun setWorking(value: Boolean) {
        if (working == value) return
        working = value
        invalidateTranscript()
    }

    private fun onState(next: JsonObject) {
        val wasStreaming = lastStateStreaming
        applyState(next)
        if (wasStreaming == true && lastStateStreaming == false) effects.signal(SessionSignal.TurnFinished(UiMappers.title(header, state)))
    }

    /** `state` is authoritative for "working" in both directions (spec 5.6). */
    private fun applyState(next: JsonObject) {
        state = next
        val streaming = next.bool("isStreaming") == true
        lastStateStreaming = streaming
        setWorking(streaming)
        if (!streaming) {
            if (activeTools.isNotEmpty()) {
                activeTools.clear()
                invalidateTranscript()
            }
            if (ghost?.done == true) {
                ghost = null
                items = null
            }
        }
    }

    private fun onHistory(frame: HostFrame.History) {
        val request = history ?: return
        if (request.reqId != frame.reqId || !welcomed) return
        clearHistoryRequest()
        if (frame.error != null) {
            toast(ToastLevel.Warning, "Could not load older messages: ${frame.error}")
            return
        }
        // Live trimming moved the window's head: this page no longer joins up with it.
        if (window.firstId() != request.cursorId) return
        val trimmed = window.prepend(frame.entries)
        hasOlder = frame.page.beforeId != null || trimmed
        invalidateTranscript()
    }

    private fun clearHistoryRequest() {
        if (history == null) return
        history = null
        disarm(SessionTimer.History)
    }

    private fun onBus(frame: HostFrame.Bus) {
        when (frame.channel) {
            "task:subagent:progress" -> frame.data.obj("progress")?.let { p -> p.str("id")?.let { progress[it] = p } }
            "task:subagent:lifecycle" -> frame.data.str("id")?.let { lifecycle[it] = frame.data }
            else -> return
        }
        agentInfos = null
    }

    /** Drops bus payloads whose agent is missing from two consecutive `agents` frames (spec 5.8). */
    private fun pruneBusMaps() {
        val listed = agents.mapNotNullTo(HashSet()) { it.str("id") }
        val previouslyUnlisted = unlistedBusIds
        val unlisted = HashSet<String>()
        for (map in listOf(progress, lifecycle)) {
            val iterator = map.keys.iterator()
            while (iterator.hasNext()) {
                val id = iterator.next()
                if (id in listed) continue
                if (id in previouslyUnlisted) iterator.remove() else unlisted += id
            }
        }
        unlistedBusIds = unlisted
    }

    private fun onUiRequest(json: JsonObject) {
        if (readOnly || !link.canWrite) return
        val request = UiMappers.request(json) ?: return
        if (pending.any { it.reqId == request.reqId }) return
        pending += request
        awaitingReplay?.remove(request.reqId)
        if (seenReqIds.add(request.reqId)) {
            while (seenReqIds.size > MAX_SEEN_REQUESTS) seenReqIds.remove(seenReqIds.first())
            effects.signal(SessionSignal.NeedsInput(request))
        }
    }

    private fun onUiRequestEnd(reqId: Long) {
        val removed = pending.removeAll { it.reqId == reqId } or (awaitingReplay?.remove(reqId) == true)
        if (removed) effects.signal(SessionSignal.RequestResolved(reqId))
    }

    /** The replay burst (stream baseline + ui-requests) ends at the first other frame after the snapshot. */
    private fun settleReplay(frame: HostFrame) {
        val waiting = awaitingReplay ?: return
        if (!welcomed || snapshot != null) return
        when (frame) {
            is HostFrame.Welcome, is HostFrame.SnapshotChunk, is HostFrame.Stream, is HostFrame.StreamDelta,
            is HostFrame.UiRequest, is HostFrame.UiRequestEnd, is HostFrame.Commands -> return
            else -> Unit
        }
        awaitingReplay = null
        for (reqId in waiting) effects.signal(SessionSignal.RequestResolved(reqId))
    }

    // ── User actions ────────────────────────────────────────────────────────────────────────

    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !canMutate) return
        effects.send(GuestFrames.prompt(trimmed))
    }

    fun abort() {
        if (canMutate) effects.send(GuestFrames.abort())
    }

    fun respond(reqId: Long, value: String?) {
        if (!canMutate) return
        if (!pending.removeAll { it.reqId == reqId }) return
        effects.send(GuestFrames.uiResponse(reqId, value))
        effects.signal(SessionSignal.RequestResolved(reqId))
    }

    fun agentCommand(agentId: String, command: AgentCommand, text: String?) {
        if (!canMutate) return
        val frame = when (command) {
            AgentCommand.Chat -> GuestFrames.agentCmd("chat", agentId, text?.trim()?.takeIf(String::isNotEmpty) ?: return)
            AgentCommand.Kill -> GuestFrames.agentCmd("kill", agentId, null)
            AgentCommand.Revive -> GuestFrames.agentCmd("revive", agentId, null)
        }
        effects.send(frame)
    }

    fun loadOlder() {
        if (!pagedHistory || phase != Phase.Live || !welcomed || !hasOlder || history != null || snapshot != null) return
        val cursor = window.firstId() ?: return
        val reqId = ++reqSeq
        history = PendingHistory(reqId, cursor)
        arm(SessionTimer.History, HISTORY_TIMEOUT_MS)
        effects.send(GuestFrames.fetchHistory(reqId, GuestFrames.HistoryCursor.Before(cursor)))
    }

    fun dismissToast(id: Long) {
        toasts.removeAll { it.id == id }
    }

    // ── Commands and models ─────────────────────────────────────────────────────────────────

    private fun hasFeature(feature: String): Boolean = feature in features

    /** The link carries a write token and the host did not mark this peer read-only. */
    private val canWriteLink: Boolean get() = link.canWrite && !readOnly

    private val canControl: Boolean get() = canMutate && hostCommands

    /**
     * v5 hosts release the welcome snapshot in chunks and send more only as earlier ones are
     * acknowledged; a guest that never acks stalls its own join (spec 4.4). Every chunk counts, an
     * empty final one included.
     */
    private fun acknowledgeChunk() {
        if (!welcomed || !GuestFrames.hasSnapshotAck(protocol)) return
        effects.send(GuestFrames.snapshotAck(++snapshotChunks))
        // The host sends a full-control guest its command list before the first chunk; when none
        // came, it has none to offer (its command sources failed).
        if (hostCommands && canWriteLink && commands == null) commands = emptyList()
    }

    fun loadModels() {
        if (!hasFeature(GuestFrames.FEATURE_MODELS)) {
            loadPicker()
            return
        }
        if (!canMutate || modelsReq != null) return
        val reqId = ++reqSeq
        modelsReq = reqId
        effects.send(GuestFrames.fetchModels(reqId))
    }

    /** Only the latest query's answer is kept; a selector never contains a space, so neither does a query. */
    fun searchModels(query: String) {
        val q = query.trim()
        if (!canPickModel || ' ' in q || q == modelSearch) return
        modelSearch = q
        pickerReqs.entries.removeAll { it.value is PickerQuery.Models }
        askPicker(PickerQuery.Models(q), "/switch $q")
    }

    private fun hostOffers(name: String): Boolean = commands?.any { it.name == name || name in it.aliases } == true

    /** The host lets this guest run `/switch`, and has no native catalog to use instead. */
    private val canPickModel: Boolean get() = canControl && !hasFeature(GuestFrames.FEATURE_MODELS) && hostOffers("switch")

    private val canPickThinking: Boolean get() = canControl && !hasFeature(GuestFrames.FEATURE_MODELS) && hostOffers("effort")

    private fun loadPicker() {
        if (pickerReqs.values.any { it == PickerQuery.Roles || it == PickerQuery.Levels }) return
        if (canPickModel) {
            pickerReqs.entries.removeAll { it.value is PickerQuery.Models }
            askPicker(PickerQuery.Models(modelSearch), "/switch $modelSearch")
            askPicker(PickerQuery.Roles, "/switch @")
        }
        if (canPickThinking) {
            askPicker(PickerQuery.Levels, "/effort ")
            probeThinking()
        }
    }

    private fun askPicker(query: PickerQuery, text: String) {
        val reqId = ++reqSeq
        pickerReqs[reqId] = query
        effects.send(GuestFrames.complete(reqId, text))
    }

    /** Which levels the current model takes; they change with the model, so this repeats after a switch. */
    private fun probeThinking() {
        if (!canPickThinking || pickerReqs.values.any { it is PickerQuery.EffortProbe }) return
        val reqId = ++reqSeq
        pickerReqs[reqId] = PickerQuery.EffortProbe()
        effects.send(GuestFrames.command(reqId, "/effort"))
    }

    private fun resetPicker() {
        pickerReqs.clear()
        pickerModels = null
        pickerRoles = null
        pickerLevels = null
        probedLevels = null
        modelSearch = ""
    }

    private val pickerLoaded: Boolean get() = pickerModels != null || pickerLevels != null || probedLevels != null

    private fun pickerCatalog(header: SessionHeaderInfo?): ModelCatalog? {
        if (!pickerLoaded) return null
        return ModelCatalog(
            models = pickerModels.orEmpty(),
            roles = pickerRoles.orEmpty(),
            currentKey = header?.modelId,
            thinkingLevel = header?.thinkingLevel,
            thinkingLevels = probedLevels ?: pickerLevels.orEmpty(),
            searchOnHost = true,
        )
    }

    private fun onPickerCompletions(query: PickerQuery, items: List<CompletionItem>) {
        when (query) {
            is PickerQuery.Models -> if (query.query == modelSearch) pickerModels = items.mapNotNull(::pickerModel)
            PickerQuery.Roles -> pickerRoles = items.mapNotNull(::pickerRole)
            PickerQuery.Levels -> pickerLevels = items.map { it.value.trim() }.filter { it.isNotEmpty() && ' ' !in it }
            is PickerQuery.EffortProbe -> Unit
        }
    }

    fun runCommand(text: String) {
        val command = text.trim()
        if (!canControl || !command.startsWith("/") || command.length < 2) return
        val name = command.drop(1).substringBefore(' ').substringBefore(':').lowercase()
        startRun(command.take(MAX_RUN_LABEL), refreshesModels = name in MODEL_COMMANDS) { GuestFrames.command(it, command) }
    }

    /** Only a command that is still producing output (a streaming `/btw` answer) stops; the host ends it `cancelled`. */
    fun cancelCommandRun(id: Long) {
        if (!canControl) return
        val reqId = runReqs.entries.firstOrNull { it.value.runId == id }?.key ?: return
        effects.send(GuestFrames.commandCancel(reqId))
    }

    /**
     * Ask for the host's argument completions of a `/name argument-prefix` draft. Only the latest
     * draft's answer is kept; a draft without an argument clears them.
     */
    fun complete(text: String) {
        if (!canControl) return
        if (!text.startsWith("/") || ' ' !in text) {
            completions = null
            completionsReq = null
            return
        }
        if (completions?.text == text || completionsReq?.text == text) return
        val reqId = ++reqSeq
        completionsReq = PendingCompletion(reqId, text)
        effects.send(GuestFrames.complete(reqId, text))
    }

    fun setModel(provider: String, id: String) {
        if (!hasFeature(GuestFrames.FEATURE_MODELS)) {
            if (!canPickModel) return
            val model = pickerModels?.firstOrNull { it.provider == provider && it.id == id }
            val selector = model?.selector ?: "$provider/$id"
            startRun("Model → ${model?.name ?: id}", refreshesModels = true) { GuestFrames.command(it, "/switch $selector") }
            return
        }
        if (!canMutate) return
        val name = models?.models?.firstOrNull { it.provider == provider && it.id == id }?.name ?: id
        startRun("Model → $name", refreshesModels = true) { GuestFrames.setModel(it, provider, id) }
    }

    fun setThinking(level: String) {
        if (!hasFeature(GuestFrames.FEATURE_MODELS)) {
            if (!canPickThinking || ' ' in level.trim() || level.isBlank()) return
            startRun("Thinking → ${level.trim()}", refreshesModels = true) { GuestFrames.command(it, "/effort ${level.trim()}") }
            return
        }
        if (!canMutate) return
        startRun("Thinking → $level", refreshesModels = true) { GuestFrames.setThinking(it, level) }
    }

    fun dismissCommandRun(id: Long) {
        runs.removeAll { it.id == id && it.status != CommandRunStatus.Running }
    }

    /** `/rewind <entryId>`: the host refuses it while the agent works; the host then resyncs every guest (spec 5.2a). */
    fun rewind(entryId: String) {
        val id = entryId.trim()
        if (!canControl || !hostOffers(REWIND_COMMAND) || id.isEmpty() || id.any(Char::isWhitespace)) return
        startRun("Rewind to an earlier prompt", refreshesModels = false, rewind = true) {
            GuestFrames.command(it, "/rewind $id")
        }
    }

    fun consumeRewoundPrompt(id: Long) {
        if (rewoundPrompt?.id == id) rewoundPrompt = null
    }

    private fun startRun(label: String, refreshesModels: Boolean, rewind: Boolean = false, frame: (reqId: Long) -> JsonObject) {
        val reqId = ++reqSeq
        val runId = ++runSeq
        runs.addLast(CommandRun(runId, label, CommandRunStatus.Running))
        while (runs.size > MAX_RUNS) runs.removeFirst()
        runReqs[reqId] = OpenRun(runId, refreshesModels, rewind)
        effects.send(frame(reqId))
    }

    /** Output blocks are separated by a blank line; a `delta` continues the previous block. */
    private fun onCommandOutput(frame: HostFrame.CommandOutput) {
        val probe = pickerReqs[frame.reqId] as? PickerQuery.EffortProbe
        if (probe != null) {
            if (!frame.delta && probe.output.isNotEmpty()) probe.output.append('\n')
            probe.output.append(frame.text)
            return
        }
        val open = runReqs[frame.reqId] ?: return
        if (frame.text.isEmpty()) return
        if (open.rewind) {
            val block = open.lastBlock
            if (frame.delta && block != null) block.append(frame.text) else open.lastBlock = StringBuilder(frame.text)
        }
        updateRun(open.runId) { run ->
            val previous = run.output
            val output = when {
                previous == null -> frame.text
                frame.delta -> previous + frame.text
                else -> previous + "\n\n" + frame.text
            }
            run.copy(output = output.take(MAX_RUN_OUTPUT))
        }
    }

    /** The single terminal reply to `command`, `set-model` and `set-thinking`. */
    private fun onCommandEnd(frame: HostFrame.CommandEnd) {
        val probe = pickerReqs[frame.reqId] as? PickerQuery.EffortProbe
        if (probe != null) {
            pickerReqs.remove(frame.reqId)
            if (frame.status == "done") parseThinkingLevels(probe.output.toString())?.let { probedLevels = it }
            return
        }
        val open = runReqs.remove(frame.reqId) ?: return
        val message = frame.message?.takeIf(String::isNotBlank)
        updateRun(open.runId) { run ->
            when (frame.status) {
                // `prompt`: handed to the agent, so it continues in the transcript.
                "done", "prompt" -> run.copy(status = CommandRunStatus.Done)
                "cancelled" -> run.copy(status = CommandRunStatus.Failed, error = message ?: "Cancelled")
                else -> run.copy(status = CommandRunStatus.Failed, error = message ?: ERR_RUN_FAILED)
            }
        }
        val prompt = open.lastBlock
        if (open.rewind && frame.status == "done" && prompt != null) rewoundPrompt = RewoundPrompt(++rewoundSeq, prompt.toString())
        // The catalog's current model and its thinking levels may have changed.
        if (open.refreshesModels && models != null) loadModels()
        if (open.refreshesModels && pickerLoaded) probeThinking()
    }

    private fun onCompletions(frame: HostFrame.Completions) {
        val picker = pickerReqs.remove(frame.reqId)
        if (picker != null) {
            onPickerCompletions(picker, frame.items)
            return
        }
        val request = completionsReq ?: return
        if (request.reqId != frame.reqId) return
        completionsReq = null
        completions = CommandCompletions(request.text, frame.items)
    }

    private fun onModels(frame: HostFrame.Models) {
        if (frame.reqId != modelsReq) return
        modelsReq = null
        val error = frame.error
        if (error != null) {
            toast(ToastLevel.Warning, "Could not load the host's models: $error")
            return
        }
        models = frame.catalog
    }

    private fun updateRun(runId: Long, change: (CommandRun) -> CommandRun) {
        val index = runs.indexOfFirst { it.id == runId }
        if (index >= 0) runs[index] = change(runs[index])
    }

    private fun toast(level: ToastLevel, text: String) {
        toasts.addLast(Toast(++toastSeq, level, text, clock()))
        while (toasts.size > MAX_TOASTS) toasts.removeFirst()
    }

    private fun level(value: String?): ToastLevel = when (value) {
        "warning" -> ToastLevel.Warning
        "error" -> ToastLevel.Error
        else -> ToastLevel.Info
    }

    // ── Timers ──────────────────────────────────────────────────────────────────────────────

    private fun arm(timer: SessionTimer, delayMs: Long) {
        val token = ++timerSeq
        timerTokens[timer] = token
        effects.schedule(timer, token, delayMs)
    }

    private fun disarm(timer: SessionTimer) {
        if (timerTokens.remove(timer) != null) effects.cancel(timer)
    }

    // ── UI state ────────────────────────────────────────────────────────────────────────────

    fun uiState(): SessionUiState {
        val headerInfo = UiMappers.header(header, state, working)
        val native = hasFeature(GuestFrames.FEATURE_MODELS)
        return SessionUiState(
            connection = ConnectionStatus(
                phase = phase,
                attempt = if (phase == Phase.Reconnecting) attempt else 0,
                nextRetryAtMs = if (phase == Phase.Reconnecting) nextRetryAtMs else null,
                message = statusMessage,
            ),
            hostLabel = "${link.relayHost} · ${link.roomId.take(6)}…",
            readOnly = readOnly || !link.canWrite,
            header = headerInfo,
            items = transcriptItems(),
            hasOlder = hasOlder && pagedHistory,
            loadingOlder = history != null,
            pending = pending.toList(),
            agents = agentInfos ?: agents.mapNotNull { UiMappers.agent(it, progress::get) }.also { agentInfos = it },
            toasts = toasts.toList(),
            controls = HostControls(
                commands = hostCommands,
                models = native,
                modelPicker = native || (hostCommands && canWriteLink && (hostOffers("switch") || hostOffers("effort"))),
                rewind = hostCommands && canWriteLink && hostOffers(REWIND_COMMAND),
            ),
            commands = commands,
            completions = completions,
            models = if (native) models else pickerCatalog(headerInfo),
            loadingModels = modelsReq != null || pickerReqs.values.any { it is PickerQuery.Models || it == PickerQuery.Roles || it == PickerQuery.Levels },
            commandRuns = runs.toList(),
            rewoundPrompt = rewoundPrompt,
        )
    }

    private fun invalidateTranscript() {
        committed = null
        items = null
    }

    private fun transcriptItems(): List<TranscriptItem> {
        items?.let { return it }
        val base = committed ?: transcript.committed(window.entries, activeTools, working).also { committed = it }
        val live = transcript.live(base, ghost, activeTools, working)
        return (if (live.isEmpty()) base.items else base.items + live).also { items = it }
    }

    companion object {
        private val PROTOCOL_MISMATCH = Regex("protocol mismatch: host speaks v(\\d+)")

        const val WELCOME_TIMEOUT_MS = 30_000L
        const val SNAPSHOT_GAP_MS = 30_000L
        const val HISTORY_TIMEOUT_MS = 10_000L
        const val MAX_TOASTS = 50
        const val MAX_RUNS = 20

        /** The host's rewind builtin; guests type it as its alias `/rewind`. */
        private const val REWIND_COMMAND = "branch"
        private const val MAX_RUN_LABEL = 80

        /** Characters of output kept per run; the host already bounds each frame. */
        private const val MAX_RUN_OUTPUT = 256 * 1024

        /** Thinking selectors a role may carry as a `:level` suffix (`provider/id:high`). */
        private val ROLE_LEVELS = setOf("off", "minimal", "low", "medium", "high", "xhigh", "max", "auto")

        /** A `/switch` completion: [CompletionItem.value] is the selector (`provider/id`), the description the model name. */
        internal fun pickerModel(item: CompletionItem): ModelInfo? {
            val selector = item.value.trim()
            if (selector.isEmpty() || ' ' in selector) return null
            val slash = selector.indexOf('/')
            val provider = if (slash > 0) selector.substring(0, slash) else ""
            val id = if (slash > 0) selector.substring(slash + 1) else selector
            val name = item.description?.trim()?.takeIf { it.isNotEmpty() } ?: item.label
            return ModelInfo(provider, id, name, reasoning = false, selector = selector)
        }

        /** A `/switch @` completion: the label is `@role`, the description the configured `provider/id[:level]`. */
        internal fun pickerRole(item: CompletionItem): RoleModel? {
            val role = item.label.trim().removePrefix("@").takeIf { it.isNotEmpty() } ?: return null
            var configured = item.description?.trim().orEmpty()
            val colon = configured.lastIndexOf(':')
            val level = if (colon > 0 && configured.substring(colon + 1) in ROLE_LEVELS) configured.substring(colon + 1) else null
            if (level != null) configured = configured.substring(0, colon)
            val slash = configured.indexOf('/')
            if (slash <= 0 || slash == configured.lastIndex) return null
            return RoleModel(role, configured.substring(0, slash), configured.substring(slash + 1), level)
        }

        /**
         * A bare `/effort` prints `Thinking: <level>` and `Available: a, b, c`, or says the model has no
         * adjustable thinking level. Null when the output is neither, so the full level list stays.
         */
        internal fun parseThinkingLevels(output: String): List<String>? {
            for (line in output.lineSequence()) {
                val text = line.trim()
                if (!text.startsWith("Available:")) continue
                return text.removePrefix("Available:").split(',').map { it.trim().trim('`') }.filter { it.isNotEmpty() }
            }
            return if ("no adjustable thinking level" in output) emptyList() else null
        }

        /** Commands after which the model catalog (current model, thinking levels) is reloaded. */
        private val MODEL_COMMANDS = setOf("model", "models", "switch", "effort", "modelpreset", "fast", "slow")

        const val ERR_RUN_LOST = "Connection lost before the host answered; it may still have run"
        const val ERR_RUN_FAILED = "The host could not run it"
        private const val MAX_SEEN_REQUESTS = 1024

        const val ERR_WELCOME_TIMEOUT = "Timed out waiting for the host's welcome"
        const val ERR_SNAPSHOT_TIMEOUT = "Timed out waiting for the host's session snapshot"
        const val ERR_STREAM_DESYNC = "Assistant stream lost synchronization. Rejoin the session to restore the current response."
        const val ERR_STREAM_PATCH = "Assistant stream patch could not be decoded. Rejoin the session to restore the current response."
    }
}
