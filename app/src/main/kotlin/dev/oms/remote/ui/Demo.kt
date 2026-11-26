package dev.oms.remote.ui

import dev.oms.remote.protocol.api.AgentInfo
import dev.oms.remote.protocol.api.AgentProgressInfo
import dev.oms.remote.protocol.api.AssistantBlock
import dev.oms.remote.protocol.api.ConnectionStatus
import dev.oms.remote.protocol.api.MarkerKind
import dev.oms.remote.protocol.api.Participant
import dev.oms.remote.protocol.api.PendingRequest
import dev.oms.remote.protocol.api.Phase
import dev.oms.remote.protocol.api.RequestKind
import dev.oms.remote.protocol.api.RequestOption
import dev.oms.remote.protocol.api.SessionHeaderInfo
import dev.oms.remote.protocol.api.SessionUiState
import dev.oms.remote.protocol.api.Toast
import dev.oms.remote.protocol.api.ToastLevel
import dev.oms.remote.protocol.api.ToolCallView
import dev.oms.remote.protocol.api.ToolStatus
import dev.oms.remote.protocol.api.TranscriptItem

/*
 * Sample data for previews and the in-app demo. Exercises every row type, tool status,
 * markers, subagents, a pending approval plus a queued ask question, and a toast.
 */

internal fun sampleApprovalRequest(): PendingRequest = PendingRequest(
    reqId = 41,
    kind = RequestKind.Select,
    title = listOf(
        "Allow tool: bash",
        "Reason: command is not in the allowlist",
        "",
        "\$ git push --force-with-lease origin fix/relay-keepalive",
        "cwd: ~/projects/oh-my-soup",
        "",
        "Provider safety checks:",
        "  - rewrites remote history (force push)",
    ).joinToString("\n"),
    options = listOf(
        RequestOption("Approve"),
        RequestOption("Approve bash for this session"),
        RequestOption("Deny"),
    ),
)

internal fun sampleAskMultiSelect(): PendingRequest = PendingRequest(
    reqId = 42,
    kind = RequestKind.Select,
    title = "Which transports should the relay keepalive cover?",
    options = listOf(
        RequestOption("WebSocket ping frames", "Send a protocol-level ping every 15 s"),
        RequestOption("Application heartbeat", "A small encrypted frame the host echoes"),
        RequestOption("TCP keepalive", "OS-level probes; slow to detect drops on mobile"),
        RequestOption(PendingRequest.OTHER),
        RequestOption(PendingRequest.NEXT),
        RequestOption(PendingRequest.CHAT),
    ),
    initialIndex = 0,
    selectionMarker = "checkbox",
    checkedIndices = setOf(0, 1),
    markableCount = 3,
)

internal fun sampleAskSingleSelect(): PendingRequest = PendingRequest(
    reqId = 43,
    kind = RequestKind.Select,
    title = "Where should the new ping interval live?",
    options = listOf(
        RequestOption("Relay config", "packages/collab-web/scripts/local-relay.ts"),
        RequestOption("Host constant", "Shared by every guest"),
        RequestOption(PendingRequest.OTHER),
        RequestOption(PendingRequest.CHAT),
    ),
    initialIndex = 1,
    selectionMarker = "radio",
    markableCount = 2,
)

internal fun sampleEditorRequest(): PendingRequest = PendingRequest(
    reqId = 44,
    kind = RequestKind.Editor,
    title = "Custom answer: Where should the new ping interval live?",
    prefill = "Put it next to the reconnect backoff",
)

private val readOutput = """
     1  import { RelayRoom } from "./room";
     2
     3  const IDLE_TIMEOUT_MS = 30_000;
     4  const PING_INTERVAL_MS = 45_000;
     5
     6  export function startRelay(port: number) {
     7  	const rooms = new Map<string, RelayRoom>();
     8  	// Idle sockets are closed after IDLE_TIMEOUT_MS without traffic.
     9  	const server = Bun.serve({ port, websocket: { idleTimeout: IDLE_TIMEOUT_MS / 1000 } });
    10  	return { server, rooms };
    11  }
    12
    13  export function pingAll(rooms: Map<string, RelayRoom>) {
    14  	for (const room of rooms.values()) room.ping();
    15  }
""".trimIndent()

private val testFailure = """
bun test v1.3.4

packages/collab-web/test/relay.test.ts:
✓ relay > routes frames between peers [3.12ms]
✓ relay > closes the room on bye [1.08ms]
✗ relay > keeps idle guests connected [30012.44ms]
  error: guest socket closed after 30000ms (code 1001)
      at <anonymous> (packages/collab-web/test/relay.test.ts:88:9)

 2 pass
 1 fail
Ran 3 tests across 1 file. [30.21s]
""".trimIndent()

private val answerMarkdown = """
Found it. The relay closes sockets that are idle for **30 s**, but guests only ping every **45 s**,
so a quiet phone on mobile data gets dropped before its first ping.

The fix is to ping more often than the idle timeout:

```ts
const IDLE_TIMEOUT_MS = 30_000;
const PING_INTERVAL_MS = 15_000; // must stay well below IDLE_TIMEOUT_MS
```

I'll also add a regression test that holds a guest idle for 40 s.
""".trimIndent()

internal fun buildDemoUiState(nowMs: Long = System.currentTimeMillis()): SessionUiState {
    val read = ToolCallView(
        toolCallId = "call_read_1",
        name = "read",
        intent = "Check the relay idle timeout",
        summary = "packages/collab-web/scripts/local-relay.ts",
        argsJson = "{\n  \"path\": \"packages/collab-web/scripts/local-relay.ts\"\n}",
        status = ToolStatus.Done,
        output = readOutput,
    )
    val grep = ToolCallView(
        toolCallId = "call_grep_1",
        name = "grep",
        intent = "Find every ping interval",
        summary = "PING_INTERVAL",
        argsJson = "{\n  \"pattern\": \"PING_INTERVAL\",\n  \"path\": \"packages\"\n}",
        status = ToolStatus.Done,
        output = "packages/collab-web/scripts/local-relay.ts:4:const PING_INTERVAL_MS = 45_000;\n" +
            "packages/collab-web/src/client.ts:61:  pingEvery: PING_INTERVAL_MS,",
    )
    val edit = ToolCallView(
        toolCallId = "call_edit_1",
        name = "edit",
        intent = "Ping more often than the idle timeout",
        summary = "packages/collab-web/scripts/local-relay.ts",
        argsJson = "{\n  \"path\": \"packages/collab-web/scripts/local-relay.ts\",\n" +
            "  \"old\": \"const PING_INTERVAL_MS = 45_000;\",\n" +
            "  \"new\": \"const PING_INTERVAL_MS = 15_000;\"\n}",
        status = ToolStatus.Done,
        output = "Applied 1 edit to packages/collab-web/scripts/local-relay.ts\n" +
            "-const PING_INTERVAL_MS = 45_000;\n+const PING_INTERVAL_MS = 15_000;",
    )
    val failingTest = ToolCallView(
        toolCallId = "call_bash_1",
        name = "bash",
        intent = "Run the relay tests",
        summary = "bun test packages/collab-web/test/relay.test.ts",
        argsJson = "{\n  \"command\": \"bun test packages/collab-web/test/relay.test.ts\",\n  \"timeout\": 120\n}",
        status = ToolStatus.Error,
        output = testFailure,
    )
    val orphan = ToolCallView(
        toolCallId = "call_read_0",
        name = "read",
        intent = null,
        summary = "packages/collab-web/src/client.ts",
        argsJson = "{\n  \"path\": \"packages/collab-web/src/client.ts\"\n}",
        status = ToolStatus.Unknown,
        output = null,
        resultMissing = true,
    )
    val running = ToolCallView(
        toolCallId = "call_bash_2",
        name = "bash",
        intent = "Re-run the relay tests with the new interval",
        summary = "bun test packages/collab-web",
        argsJson = "{\n  \"command\": \"bun test packages/collab-web\"\n}",
        status = ToolStatus.Running,
        output = "bun test v1.3.4\n\npackages/collab-web/test/relay.test.ts:\n✓ relay > routes frames between peers [2.98ms]",
    )
    val screenshot = ToolCallView(
        toolCallId = "call_web_1",
        name = "web_search",
        intent = "Check Bun's default WebSocket idle timeout",
        summary = "bun serve websocket idleTimeout default",
        argsJson = "{\n  \"query\": \"bun serve websocket idleTimeout default\"\n}",
        status = ToolStatus.Done,
        output = "Bun.serve websocket.idleTimeout defaults to 120 seconds; 0 disables it.",
        imageCount = 1,
    )

    val items = listOf(
        TranscriptItem.Marker("m-model", MarkerKind.ModelChange, "model → anthropic/claude-opus-4-5"),
        TranscriptItem.Tool("t-orphan", orphan),
        TranscriptItem.User(
            key = "u1",
            text = "Guests on mobile data get dropped after ~30 s. Can you find out why and fix it?",
        ),
        TranscriptItem.Assistant(
            key = "a1",
            blocks = listOf(
                AssistantBlock.Thinking(
                    "Thirty seconds smells like an idle timeout. Check the relay's idle settings " +
                        "and compare with how often guests ping.",
                ),
                AssistantBlock.Text("Thirty seconds sounds like an idle timeout. Let me check the relay."),
                AssistantBlock.Tool(read),
                AssistantBlock.Tool(grep),
                AssistantBlock.Tool(screenshot),
            ),
        ),
        TranscriptItem.Assistant(
            key = "a2",
            blocks = listOf(
                AssistantBlock.Text(answerMarkdown),
                AssistantBlock.Tool(edit),
                AssistantBlock.Tool(failingTest),
            ),
        ),
        TranscriptItem.Assistant(
            key = "a3",
            blocks = listOf(AssistantBlock.Text("The existing test still waits on the old interval.")),
            stopReason = "error",
            errorMessage = "overloaded_error: the API is temporarily overloaded, retrying",
        ),
        TranscriptItem.Marker("m-compact", MarkerKind.Compaction, "context compacted · 128,400 tokens"),
        TranscriptItem.User(
            key = "u2",
            text = "Also bump the reconnect backoff cap to 10 s please. Screenshot of the drop attached.",
            imageCount = 1,
            from = "Pixel 9",
        ),
        TranscriptItem.Custom(
            key = "c1",
            customType = "reminder",
            text = "Remember to update the relay docs in docs/collab-v5-spec.md.",
        ),
        TranscriptItem.Marker("m-thinking", MarkerKind.ThinkingChange, "thinking → high"),
        TranscriptItem.Assistant(
            key = "ghost",
            blocks = listOf(
                AssistantBlock.Thinking("", redacted = true),
                AssistantBlock.Text("I'll update the backoff cap and re-run the suite:"),
                AssistantBlock.Tool(running),
            ),
            streaming = true,
        ),
    )

    val header = SessionHeaderInfo(
        title = "Fix relay keepalive",
        cwd = "/home/dev/projects/oh-my-soup/packages/collab-web",
        modelName = "Claude Opus 4.5",
        modelId = "anthropic/claude-opus-4-5",
        thinkingLevel = "high",
        contextPercent = 42.0,
        contextTokens = 84_000,
        contextWindow = 200_000,
        isStreaming = true,
        isAborting = false,
        queuedCount = 1,
        participants = listOf(
            Participant(name = "host", role = "host", readOnly = false),
            Participant(name = "Pixel 9", role = "guest", readOnly = false),
        ),
    )

    val agents = listOf(
        AgentInfo(
            id = "main",
            displayName = "oms",
            kind = "main",
            parentId = null,
            status = "running",
            hasSessionFile = true,
            lastActivityMs = nowMs - 2_000,
        ),
        AgentInfo(
            id = "sub-explore",
            displayName = "explore-relay",
            kind = "sub",
            parentId = "main",
            status = "running",
            hasSessionFile = true,
            lastActivityMs = nowMs - 4_000,
            progress = AgentProgressInfo(
                task = "Map every place that sets a WebSocket timeout or ping interval",
                status = "running",
                currentTool = "grep",
                lastIntent = "Search client reconnect code",
                toolCount = 14,
                tokens = 48_200,
                cost = 0.21,
                durationMs = 95_000,
                recentOutput = listOf("src/client.ts:61: pingEvery: PING_INTERVAL_MS,", "src/backoff.ts:12: maxDelayMs: 5_000"),
            ),
        ),
        AgentInfo(
            id = "sub-tests",
            displayName = "test-runner",
            kind = "sub",
            parentId = "main",
            status = "parked",
            hasSessionFile = true,
            lastActivityMs = nowMs - 8 * 60_000,
            progress = AgentProgressInfo(
                task = "Run the collab-web suite and summarize failures",
                status = "done",
                currentTool = null,
                lastIntent = "Summarized 1 failing test",
                toolCount = 6,
                tokens = 12_900,
                cost = 0.04,
                durationMs = 41_000,
            ),
        ),
    )

    return SessionUiState(
        connection = ConnectionStatus(Phase.Live),
        hostLabel = "laptop.tail1234.ts.net · AbC123…",
        readOnly = false,
        header = header,
        items = items,
        hasOlder = true,
        loadingOlder = false,
        pending = listOf(sampleApprovalRequest(), sampleAskMultiSelect()),
        agents = agents,
        toasts = listOf(Toast(id = 1, level = ToastLevel.Info, text = "retry 1/3: overloaded_error", atMs = nowMs)),
    )
}
