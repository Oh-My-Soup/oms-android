package dev.oms.remote.protocol.api

/*
 * UI-facing contract between the protocol module and the Android app.
 *
 * The protocol module reduces collab v5 frames (see docs/collab-v5-spec.md) into
 * [SessionUiState]. The app only renders this state and calls [RemoteActions]; it never sees
 * wire frames. Treat every type here as a stable contract: add fields only with defaults.
 */

/** Connection lifecycle of one collab room. */
enum class Phase {
    /** Socket opening or waiting for `welcome` / snapshot. */
    Connecting,

    /** Welcome and snapshot received; frames are live. Mutating actions are allowed (unless read-only). */
    Live,

    /** Socket dropped (or relay said room-closed); retrying with backoff. */
    Reconnecting,

    /** Host ended the room (`bye`) or the user disconnected. Terminal: needs a new link. */
    Ended,

    /** Fatal error (bad link, decrypt failure, protocol mismatch, stream desync). Terminal. */
    Failed,
}

data class ConnectionStatus(
    val phase: Phase,
    /** Reconnect attempt number while [Phase.Reconnecting], else 0. */
    val attempt: Int = 0,
    /** Epoch millis of the next retry while [Phase.Reconnecting]. */
    val nextRetryAtMs: Long? = null,
    /** Human-readable reason for [Phase.Ended] / [Phase.Failed] (e.g. the `bye` reason). */
    val message: String? = null,
)

data class Participant(val name: String, val role: String, val readOnly: Boolean)

/** Header/footer facts: from `welcome.header` plus the latest `state` frame. */
data class SessionHeaderInfo(
    /** `header.title ?: state.sessionName ?: "session"`. */
    val title: String,
    val cwd: String?,
    /** `state.model.name`. */
    val modelName: String?,
    /** `"<provider>/<id>"` of `state.model`. */
    val modelId: String?,
    /** off|minimal|low|medium|high|xhigh|max, or null. */
    val thinkingLevel: String?,
    /** 0..100, or null when unknown. */
    val contextPercent: Double?,
    val contextTokens: Long?,
    val contextWindow: Long?,
    /** Authoritative "agent is working" flag. */
    val isStreaming: Boolean,
    val isAborting: Boolean,
    val queuedCount: Int,
    val participants: List<Participant>,
)

enum class ToolStatus { Running, Done, Error, Unknown }

/** One tool call merged with its live execution events and its result message. */
data class ToolCallView(
    val toolCallId: String,
    val name: String,
    /** The model-supplied intent (`"i"` key or `intent`), shown as a caption. */
    val intent: String?,
    /** One-line summary for the collapsed card: command for bash, path for read/edit/write, pattern for grep, else first arg. */
    val summary: String,
    /** Pretty-printed arguments JSON with the `"i"` key removed. */
    val argsJson: String,
    val status: ToolStatus,
    /** Joined text content of the result (or of `partialResult` while running), or null when none yet. */
    val output: String?,
    /** Number of image blocks in the result. */
    val imageCount: Int = 0,
    /** True when the call is committed but its result is not in the loaded window ("Result not on this page"). */
    val resultMissing: Boolean = false,
)

sealed interface AssistantBlock {
    data class Text(val markdown: String) : AssistantBlock

    data class Thinking(val text: String, val redacted: Boolean = false) : AssistantBlock

    data class Tool(val call: ToolCallView) : AssistantBlock
}

enum class MarkerKind { Compaction, BranchSummary, ModelChange, ThinkingChange }

/** Rows of the transcript, oldest first. [key] is unique and stable across recompositions. */
sealed interface TranscriptItem {
    val key: String

    /** A prompt. [from] is null for the host's own prompts, else the guest display name. */
    data class User(
        override val key: String,
        val text: String,
        val imageCount: Int = 0,
        val from: String? = null,
    ) : TranscriptItem

    data class Assistant(
        override val key: String,
        val blocks: List<AssistantBlock>,
        /** True for the live stream ghost (not yet committed). */
        val streaming: Boolean = false,
        /** `error` or `aborted` adds an error chip; otherwise null/ignored. */
        val stopReason: String? = null,
        val errorMessage: String? = null,
    ) : TranscriptItem

    /** A tool card not attached to an assistant message (orphan result or active tool). */
    data class Tool(override val key: String, val call: ToolCallView) : TranscriptItem

    /** A `custom_message` with `display:true` (other than collab-prompt). */
    data class Custom(override val key: String, val customType: String, val text: String) : TranscriptItem

    /** Divider/marker rows: compaction, branch summary, model change, thinking change. */
    data class Marker(override val key: String, val kind: MarkerKind, val text: String) : TranscriptItem
}

data class RequestOption(val label: String, val description: String? = null)

enum class RequestKind { Select, Editor }

/** A mirrored host dialog (`ui-request`). Answer with [RemoteActions.respond]. */
data class PendingRequest(
    val reqId: Long,
    val kind: RequestKind,
    val title: String,
    val options: List<RequestOption> = emptyList(),
    val initialIndex: Int? = null,
    /** "radio" or "checkbox"; markers only on options with index < [markableCount]. */
    val selectionMarker: String? = null,
    val checkedIndices: Set<Int> = emptySet(),
    val markableCount: Int = 0,
    /** Editor prefill. */
    val prefill: String? = null,
) {
    /** Tool approval prompts start with "Allow tool: ". */
    val isApproval: Boolean get() = kind == RequestKind.Select && title.startsWith("Allow tool: ")

    /** The tool name of an approval, or null. */
    val approvalTool: String? get() = if (isApproval) title.lineSequence().first().removePrefix("Allow tool: ").trim() else null

    /** The one-shot approve label ("Approve Once" or "Approve"), if present. */
    val approveOnceLabel: String? get() = options.firstOrNull { it.label == "Approve Once" }?.label
        ?: options.firstOrNull { it.label == "Approve" }?.label

    val denyLabel: String? get() = options.firstOrNull { it.label == "Deny" }?.label

    companion object {
        const val OTHER = "Other (type your own)"
        const val CHAT = "Chat about this"
        const val NEXT = "Next →"
    }
}

data class AgentProgressInfo(
    val task: String?,
    val status: String,
    val currentTool: String?,
    val lastIntent: String?,
    val toolCount: Int,
    val tokens: Long,
    val cost: Double,
    val durationMs: Long,
    val recentOutput: List<String> = emptyList(),
)

data class AgentInfo(
    val id: String,
    val displayName: String,
    /** "main" or "sub". */
    val kind: String,
    val parentId: String?,
    /** running|idle|parked|aborted. */
    val status: String,
    val hasSessionFile: Boolean,
    val lastActivityMs: Long?,
    val progress: AgentProgressInfo? = null,
)

enum class ToastLevel { Info, Warning, Error }

data class Toast(val id: Long, val level: ToastLevel, val text: String, val atMs: Long)

/** What the host lets this guest drive beyond prompts. */
data class HostControls(
    /** The host runs slash commands sent by guests with a full link (collab v5, oms 18.20.3 and newer). */
    val commands: Boolean = false,
    /** The host lists its models and switches model/thinking for this guest (`welcome.features` has `models`). */
    val models: Boolean = false,
    /**
     * The model & thinking sheet works: natively ([models]) or, on a v5 host without that feature,
     * through the `/switch` and `/effort` commands the host lets this guest run.
     */
    val modelPicker: Boolean = false,
    /** The host lets this guest rewind its session ([RemoteActions.rewind]). */
    val rewind: Boolean = false,
)

/** The prompt a finished [RemoteActions.rewind] went back past, to put in the composer once. */
data class RewoundPrompt(val id: Long, val text: String)

data class SubcommandInfo(val name: String, val description: String? = null, val usage: String? = null)

/** One slash command the host accepts from guests. */
data class CommandInfo(
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String? = null,
    /** Argument hint such as `[level]`. */
    val hint: String? = null,
    val subcommands: List<SubcommandInfo> = emptyList(),
    /** builtin|skill|custom|mcp_prompt|file|template. */
    val source: String = "builtin",
) {
    /** True when the command is usually followed by arguments. */
    val takesArgs: Boolean get() = hint != null || subcommands.isNotEmpty()
}

/** One argument completion from the host; [value] replaces everything after the command name and its space. */
data class CompletionItem(val value: String, val label: String, val description: String? = null)

/** The host's argument completions for the draft [text]. */
data class CommandCompletions(val text: String, val items: List<CompletionItem>)

data class ModelInfo(
    val provider: String,
    val id: String,
    val name: String,
    val reasoning: Boolean,
    val contextWindow: Long? = null,
    /** In the host user's enabled-model scope. */
    val scoped: Boolean = false,
    /** The host's `/switch` selector for this model; set when the catalog comes from command completions. */
    val selector: String? = null,
) {
    val key: String get() = "$provider/$id"
}

/** A configured quick role (`default`, `smol`, …) resolved to a model. */
data class RoleModel(val role: String, val provider: String, val id: String, val thinkingLevel: String? = null) {
    val key: String get() = "$provider/$id"
}

data class ModelCatalog(
    val models: List<ModelInfo>,
    val roles: List<RoleModel>,
    /** `provider/id` of the session model. */
    val currentKey: String?,
    val thinkingLevel: String?,
    /** Thinking selectors the current model accepts (e.g. off, auto, low, high). */
    val thinkingLevels: List<String>,
    /**
     * [models] are the host's own matches for the last [RemoteActions.searchModels] query, best first;
     * the UI sends its search to the host instead of filtering locally.
     */
    val searchOnHost: Boolean = false,
)

enum class CommandRunStatus { Running, Done, Failed }

/** A command, model switch or thinking change this guest sent, and the host's answer. */
data class CommandRun(
    val id: Long,
    /** What was run, e.g. `/compact` or `Model → Kimi K3.1 Pro`. */
    val label: String,
    val status: CommandRunStatus,
    /** Markdown output the host sent for it so far. */
    val output: String? = null,
    val error: String? = null,
)

data class SessionUiState(
    val connection: ConnectionStatus,
    /** Short label for the relay/room, e.g. "laptop.tail1234.ts.net · AbC123…". */
    val hostLabel: String,
    val readOnly: Boolean = false,
    val header: SessionHeaderInfo? = null,
    val items: List<TranscriptItem> = emptyList(),
    /** True when older entries exist before the first loaded one. */
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    /** FIFO queue of host dialogs; the UI shows the first. Empty for read-only links. */
    val pending: List<PendingRequest> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
    /** At most 50, newest last. */
    val toasts: List<Toast> = emptyList(),
    val controls: HostControls = HostControls(),
    /** The host's slash commands; null until the host sent them (or when [HostControls.commands] is false). */
    val commands: List<CommandInfo>? = null,
    /** The host's completions for the latest draft passed to [RemoteActions.complete]; match [CommandCompletions.text]. */
    val completions: CommandCompletions? = null,
    /** The host's model catalog; null until [RemoteActions.loadModels] answered. */
    val models: ModelCatalog? = null,
    val loadingModels: Boolean = false,
    /** Recent command/model/thinking requests, newest last (at most 20). */
    val commandRuns: List<CommandRun> = emptyList(),
    /** Set when a rewind finished; hand it to [RemoteActions.consumeRewoundPrompt] once it is in the composer. */
    val rewoundPrompt: RewoundPrompt? = null,
)

enum class AgentCommand { Chat, Kill, Revive }

/** Everything the UI can ask the session to do. All calls are non-blocking and safe from the main thread. */
interface RemoteActions {
    /** Send a prompt (steers when the host is busy). Ignored unless live and writable; blank text is ignored. */
    fun sendPrompt(text: String)

    /** Interrupt the current turn. */
    fun abort()

    /** Answer [PendingRequest] [reqId] with an option label or editor text; null cancels. Removes it locally at once. */
    fun respond(reqId: Long, value: String?)

    /** Subagent control; [text] is required for [AgentCommand.Chat]. */
    fun agentCommand(agentId: String, command: AgentCommand, text: String? = null)

    /** Fetch the page before the first loaded entry (no-op unless [SessionUiState.hasOlder]). */
    fun loadOlder()

    fun dismissToast(id: Long)

    /** Retry now (skips the backoff wait) while reconnecting. */
    fun reconnectNow()

    /** Run a slash command (`/name args`) on the host. Needs [HostControls.commands] and a writable link. */
    fun runCommand(text: String) {}

    /** Stop a running [CommandRun] (a streaming `/btw` answer); the host ends it as cancelled. */
    fun cancelCommandRun(id: Long) {}

    /** Ask the host for argument completions of a `/name argument-prefix` draft; see [SessionUiState.completions]. */
    fun complete(text: String) {}

    /** (Re)load [SessionUiState.models]. Needs [HostControls.modelPicker]. */
    fun loadModels() {}

    /** Ask the host for models matching [query]; only for a [ModelCatalog.searchOnHost] catalog. */
    fun searchModels(query: String) {}

    /** Switch the session model (session-only, like `/switch` on the host). */
    fun setModel(provider: String, id: String) {}

    /** Set the thinking level to one of [ModelCatalog.thinkingLevels]. */
    fun setThinking(level: String) {}

    /** Hide a finished [CommandRun]. */
    fun dismissCommandRun(id: Long) {}

    /**
     * Rewind the host session past the prompt [entryId] (a [TranscriptItem.User] key) with
     * `/rewind`; the later turns stay on the host as a branch. Needs [HostControls.rewind]. Its text
     * comes back as [SessionUiState.rewoundPrompt].
     */
    fun rewind(entryId: String) {}

    /** The composer took [RewoundPrompt] [id]. */
    fun consumeRewoundPrompt(id: Long) {}
}

/** One-shot events for notifications. */
sealed interface SessionSignal {
    /** A new host dialog arrived (not replays of ones already signalled). */
    data class NeedsInput(val request: PendingRequest) : SessionSignal

    /** A dialog was answered/withdrawn (from any participant). */
    data class RequestResolved(val reqId: Long) : SessionSignal

    /** The agent went from working to idle (`state.isStreaming` true -> false). */
    data class TurnFinished(val title: String?) : SessionSignal

    data class Ended(val reason: String) : SessionSignal

    data class Failed(val message: String) : SessionSignal
}
