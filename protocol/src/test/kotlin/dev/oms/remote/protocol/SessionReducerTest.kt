package dev.oms.remote.protocol

import dev.oms.remote.protocol.api.AgentCommand
import dev.oms.remote.protocol.api.AssistantBlock
import dev.oms.remote.protocol.api.CommandRunStatus
import dev.oms.remote.protocol.api.MarkerKind
import dev.oms.remote.protocol.api.Phase
import dev.oms.remote.protocol.api.SessionSignal
import dev.oms.remote.protocol.api.ToastLevel
import dev.oms.remote.protocol.api.ToolStatus
import dev.oms.remote.protocol.api.TranscriptItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionReducerTest {
    private val effects = RecordingEffects()

    private fun reducer(writable: Boolean = true) =
        SessionReducer(Fixtures.link(writable = writable), "pixel", effects, clock = { 1_000L }).apply {
            start()
            onSocketOpen()
        }

    private fun SessionReducer.feed(vararg frames: String) = frames.forEach { onFrame(HostFrames.decode(it)) }

    private fun welcome(entryCount: Int, beforeId: String? = null, readOnly: Boolean = false, streaming: Boolean = false): String {
        val history = """{"beforeId":${beforeId?.let { "\"$it\"" } ?: "null"},"afterId":null,"total":${entryCount + 10}}"""
        val ro = if (readOnly) ""","readOnly":true""" else ""
        return """{"t":"welcome","proto":5,"header":{"type":"session","id":"s1","title":"Fix login","cwd":"/home/u/proj"},""" +
            """"state":${state(streaming)},"agents":[],"entryCount":$entryCount$ro,"history":$history}"""
    }

    private fun state(streaming: Boolean) =
        """{"isStreaming":$streaming,"isAborting":false,"queuedMessageCount":0,"sessionName":"s","cwd":"/home/u/proj",""" +
            """"model":{"id":"claude-x","name":"Claude X","provider":"anthropic","contextWindow":200000},"thinkingLevel":"high",""" +
            """"contextUsage":{"tokens":50000,"contextWindow":200000,"percent":25},"participants":[{"name":"alice","role":"host"}]}"""

    private fun chunk(final: Boolean, vararg entries: String) = """{"t":"snapshot-chunk","entries":[${entries.joinToString(",")}],"final":$final}"""

    private fun user(id: String, text: String) = """{"type":"message","id":"$id","parentId":null,"timestamp":"t","message":{"role":"user","content":"$text"}}"""

    private fun assistant(id: String, content: String, stop: String = "stop") =
        """{"type":"message","id":"$id","parentId":null,"timestamp":"t","message":{"role":"assistant","content":$content,"stopReason":"$stop"}}"""

    private fun entryFrame(entry: String) = """{"t":"entry","entry":$entry}"""

    private fun event(json: String) = """{"t":"event","event":$json}"""

    private fun uiRequest(reqId: Int, title: String = "Allow tool: bash\nReason: runs tests", options: String = """["Approve","Approve bash for this session","Deny"]""") =
        """{"t":"ui-request","request":{"reqId":$reqId,"kind":"select","title":${encodeJson(kotlinx.serialization.json.JsonPrimitive(title))},"options":$options}}"""

    private fun keys(r: SessionReducer) = r.uiState().items.map { it.key }

    private fun v3Welcome(entryCount: Int) =
        """{"t":"welcome","proto":3,"header":{"type":"session","id":"s1","title":"Fix login"},"state":${state(true)},"agents":[],"entryCount":$entryCount}"""

    /** An older host refuses the v5 hello; the reducer switches and the owner opens a new socket. */
    private fun SessionReducer.downgradeTo(version: Int) {
        feed("""{"t":"error","message":"protocol mismatch: host speaks v$version, guest sent v5"}""")
        onSocketOpen()
    }

    private fun SessionReducer.downgradeToV3() = downgradeTo(3)

    @Test
    fun snapshotRendersRowsWithToolFoldingAndMarkers() {
        val r = reducer()
        val bashArgs = """{"command":"bun test\n--watch","i":"run the suite"}"""
        r.feed(
            welcome(entryCount = 12, beforeId = "e1"),
            chunk(
                false,
                user("e1", "hello"),
                """{"type":"custom_message","id":"e2","customType":"collab-prompt","content":"run tests","display":true,"details":{"from":"Pixel"}}""",
                assistant(
                    "e3",
                    """[{"type":"thinking","thinking":"plan"},{"type":"text","text":"Running"},""" +
                        """{"type":"toolCall","id":"c1","name":"bash","arguments":$bashArgs},""" +
                        """{"type":"toolCall","id":"c2","name":"read","arguments":{"path":"src/a.ts"}}]""",
                    stop = "toolUse",
                ),
                """{"type":"message","id":"e4","message":{"role":"toolResult","toolCallId":"c1","toolName":"bash","content":[{"type":"text","text":"ok"}],"isError":false}}""",
                """{"type":"message","id":"e5","message":{"role":"toolResult","toolCallId":"cX","toolName":"grep","content":[{"type":"text","text":"match"},"…[3 items elided for collab session]"],"isError":true}}""",
                """{"type":"compaction","id":"e6","summary":"s","firstKeptEntryId":"e1","tokensBefore":12345}""",
            ),
            chunk(
                true,
                """{"type":"model_change","id":"e7","model":"anthropic/claude-x"}""",
                """{"type":"thinking_level_change","id":"e8","thinkingLevel":null}""",
                """{"type":"custom_message","id":"e9","customType":"hidden","content":"x","display":false}""",
                """{"type":"custom_message","id":"e10","customType":"note","content":[{"type":"text","text":"hi"}],"display":true}""",
                """{"type":"label","id":"e11"}""",
                """{"type":"message","id":"e12","message":{"role":"developer","content":"sys"}}""",
            ),
        )
        val ui = r.uiState()
        assertEquals(Phase.Live, ui.connection.phase)
        assertTrue(ui.hasOlder)
        assertEquals("Fix login", ui.header?.title)
        assertEquals("anthropic/claude-x", ui.header?.modelId)
        assertEquals(listOf("e1", "e2", "e3", "e5", "e6", "e7", "e8", "e10"), ui.items.map { it.key })

        assertEquals(TranscriptItem.User("e1", "hello"), ui.items[0])
        assertEquals(TranscriptItem.User("e2", "run tests", from = "Pixel"), ui.items[1])

        val turn = assertIs<TranscriptItem.Assistant>(ui.items[2])
        assertEquals(listOf(AssistantBlock.Thinking("plan"), AssistantBlock.Text("Running")), turn.blocks.take(2))
        val bash = assertIs<AssistantBlock.Tool>(turn.blocks[2]).call
        assertEquals("run the suite", bash.intent)
        assertEquals("bun test", bash.summary)
        assertEquals(Fixtures.json("""{"command":"bun test\n--watch"}"""), Fixtures.json(bash.argsJson))
        assertEquals(ToolStatus.Done, bash.status)
        assertEquals("ok", bash.output)
        val read = assertIs<AssistantBlock.Tool>(turn.blocks[3]).call
        assertEquals("src/a.ts", read.summary)
        assertTrue(read.resultMissing, "a committed call without result while idle is 'not on this page'")

        val orphan = assertIs<TranscriptItem.Tool>(ui.items[3]).call
        assertEquals("grep", orphan.name)
        assertEquals(ToolStatus.Error, orphan.status)
        assertEquals("match\n…[3 items elided for collab session]", orphan.output)

        assertEquals(TranscriptItem.Marker("e6", MarkerKind.Compaction, "context compacted · 12.3k tokens"), ui.items[4])
        assertEquals(TranscriptItem.Marker("e7", MarkerKind.ModelChange, "model → anthropic/claude-x"), ui.items[5])
        assertEquals(TranscriptItem.Marker("e8", MarkerKind.ThinkingChange, "thinking → off"), ui.items[6])
        assertEquals(TranscriptItem.Custom("e10", "note", "hi"), ui.items[7])
    }

    @Test
    fun liveEntriesDuringSnapshotAreMergedOnceAfterIt() {
        val r = reducer()
        r.feed(welcome(entryCount = 2), chunk(false, user("e1", "a")))
        assertEquals(Phase.Connecting, r.uiState().connection.phase)
        r.feed(entryFrame(user("e2", "b")), entryFrame(user("e3", "c")), chunk(true, user("e2", "b")))
        assertEquals(Phase.Live, r.uiState().connection.phase)
        assertEquals(listOf("e1", "e2", "e3"), keys(r))
        r.feed(entryFrame(user("e3", "c")))
        assertEquals(listOf("e1", "e2", "e3"), keys(r))
    }

    @Test
    fun streamGhostIsReplacedByTheCommittedEntry() {
        val r = reducer()
        r.feed(welcome(entryCount = 1, streaming = true), chunk(true, user("e1", "hi")))
        r.feed(event("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""))
        r.feed("""{"t":"stream","id":1,"revision":0,"message":{"role":"assistant","content":[{"type":"text","text":"Hel"}]}}""")
        val ghostKey = keys(r).last()
        assertEquals(listOf("e1", ghostKey), keys(r))

        r.feed(
            """{"t":"stream-delta","id":1,"previousRevision":0,"revision":1,"patch":{"metadata":{"role":"assistant"},"contentLength":1,""" +
                """"changes":[{"index":0,"kind":"append","field":"text","offset":3,"text":"lo","properties":{"type":"text"}}]}}""",
        )
        val ghost = assertIs<TranscriptItem.Assistant>(r.uiState().items.last())
        assertEquals(ghostKey, ghost.key)
        assertTrue(ghost.streaming)
        assertEquals(listOf(AssistantBlock.Text("Hello")), ghost.blocks)

        r.feed(event("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Hello"}],"stopReason":"stop"}}"""))
        assertEquals(listOf("e1", ghostKey), keys(r), "the finished ghost stays until its entry lands")

        r.feed(entryFrame(assistant("e2", """[{"type":"text","text":"Hello"}]""")))
        assertEquals(listOf("e1", "e2"), keys(r))
        assertFalse(assertIs<TranscriptItem.Assistant>(r.uiState().items.last()).streaming)
    }

    @Test
    fun streamDeltaWithoutMatchingBaselineFailsTheSession() {
        val r = reducer()
        r.feed(welcome(entryCount = 0))
        r.feed("""{"t":"stream","id":1,"revision":0,"message":{"role":"assistant","content":[]}}""")
        r.feed("""{"t":"stream-delta","id":1,"previousRevision":2,"revision":3,"patch":{"metadata":{},"contentLength":0,"changes":[]}}""")
        assertEquals(Phase.Failed, r.uiState().connection.phase)
        assertTrue("rejoin" in effects.signals.filterIsInstance<SessionSignal.Failed>().single().message.lowercase())
        assertTrue(effects.shutdown)
    }

    @Test
    fun runningToolsShowPartialOutputAndUnreferencedOnesTrailTheTranscript() {
        val r = reducer()
        r.feed(welcome(entryCount = 0, streaming = true))
        r.feed(event("""{"type":"tool_execution_start","toolCallId":"t9","toolName":"bash","args":{"command":"ls"},"intent":"list"}"""))
        r.feed(event("""{"type":"tool_execution_update","toolCallId":"t9","toolName":"bash","args":{},"partialResult":{"content":[{"type":"text","text":"a.txt"}]}}"""))
        val tail = assertIs<TranscriptItem.Tool>(r.uiState().items.single())
        assertEquals(ToolStatus.Running, tail.call.status)
        assertEquals("a.txt", tail.call.output)
        assertEquals("list", tail.call.intent)
        r.feed(event("""{"type":"tool_execution_end","toolCallId":"t9","toolName":"bash","result":{"content":[]}}"""))
        assertTrue(r.uiState().items.isEmpty())
    }

    @Test
    fun approvalRequestIsAnsweredAndRemovedLocally() {
        val r = reducer()
        r.feed(welcome(entryCount = 0), uiRequest(9))
        val request = r.uiState().pending.single()
        assertEquals("bash", request.approvalTool)
        assertEquals("Approve", request.approveOnceLabel)
        assertEquals(SessionSignal.NeedsInput(request), effects.signals.last())

        r.respond(9, "Approve")
        assertTrue(r.uiState().pending.isEmpty())
        assertEquals(Fixtures.json("""{"t":"ui-response","reqId":9,"value":"Approve"}"""), effects.sent.last())

        r.feed(uiRequest(10))
        r.respond(10, null)
        assertEquals(Fixtures.json("""{"t":"ui-response","reqId":10}"""), effects.sent.last())

        r.feed(uiRequest(11))
        r.feed("""{"t":"ui-request-end","reqId":11}""")
        assertTrue(r.uiState().pending.isEmpty())
        assertEquals(SessionSignal.RequestResolved(11), effects.signals.last())
    }

    @Test
    fun replayedRequestsAfterReconnectDoNotNotifyAgain() {
        val r = reducer()
        r.feed(welcome(entryCount = 0), uiRequest(5), uiRequest(7))
        r.onSocketLost("connection lost")
        r.onSocketOpen()
        r.feed(welcome(entryCount = 0), uiRequest(5), uiRequest(6))
        assertEquals(listOf(5L, 6L), r.uiState().pending.map { it.reqId })
        assertEquals(listOf(5L, 7L, 6L), effects.signals.filterIsInstance<SessionSignal.NeedsInput>().map { it.request.reqId })
        // Request 7 was settled while we were away: the replay burst ends without it.
        r.feed("""{"t":"state","state":${state(false)}}""")
        assertEquals(listOf(SessionSignal.RequestResolved(7)), effects.signals.filterIsInstance<SessionSignal.RequestResolved>())
    }

    @Test
    fun historyPagePrependsOlderEntries() {
        val r = reducer()
        r.feed(welcome(entryCount = 2, beforeId = "e3"), chunk(true, user("e3", "c"), user("e4", "d")))
        assertTrue(r.uiState().hasOlder)
        r.loadOlder()
        r.loadOlder()
        val fetches = effects.sent.filter { it.str("t") == "fetch-history" }
        assertEquals(listOf(Fixtures.json("""{"t":"fetch-history","reqId":1,"cursor":{"kind":"before","id":"e3"}}""")), fetches)
        assertTrue(r.uiState().loadingOlder)

        r.feed("""{"t":"history","reqId":1,"entries":[${user("e1", "a")},${user("e2", "b")}],"page":{"beforeId":null,"afterId":"e2","total":4}}""")
        val ui = r.uiState()
        assertEquals(listOf("e1", "e2", "e3", "e4"), ui.items.map { it.key })
        assertFalse(ui.hasOlder)
        assertFalse(ui.loadingOlder)
        r.loadOlder()
        assertEquals(1, effects.sent.count { it.str("t") == "fetch-history" })
    }

    @Test
    fun mutationsNeedALiveWritableSession() {
        val viewOnly = reducer(writable = false)
        viewOnly.sendPrompt("hi")
        viewOnly.feed(welcome(entryCount = 0, readOnly = true), uiRequest(1))
        viewOnly.sendPrompt("hi")
        viewOnly.abort()
        viewOnly.agentCommand("0-Explore", AgentCommand.Kill, null)
        assertTrue(viewOnly.uiState().readOnly)
        assertTrue(viewOnly.uiState().pending.isEmpty())
        assertEquals(listOf("hello"), effects.sent.map { it.str("t") })

        effects.sent.clear()
        val writable = reducer()
        writable.sendPrompt("too early")
        writable.feed(welcome(entryCount = 0))
        writable.sendPrompt("   ")
        writable.sendPrompt("  run tests  ")
        writable.agentCommand("0-Explore", AgentCommand.Chat, "focus")
        assertEquals(
            listOf(
                Fixtures.json("""{"t":"hello","proto":5,"name":"pixel","history":"paged","writeToken":"oKGio6SlpqeoqaqrrK2urw"}"""),
                Fixtures.json("""{"t":"prompt","text":"run tests"}"""),
                Fixtures.json("""{"t":"agent-cmd","cmd":"chat","agentId":"0-Explore","text":"focus"}"""),
            ),
            effects.sent,
        )
    }

    @Test
    fun errorBeforeWelcomeIsFatalAndAfterWelcomeIsAToast() {
        val early = reducer()
        early.feed("""{"t":"error","message":"Session transition in progress; join again when it completes"}""")
        assertEquals(Phase.Failed, early.uiState().connection.phase)
        assertEquals("Session transition in progress; join again when it completes", early.uiState().connection.message)

        val live = SessionReducer(Fixtures.link(), "pixel", RecordingEffects(), clock = { 1_000L })
        live.onSocketOpen()
        live.feed(welcome(entryCount = 0), """{"t":"error","message":"prompt failed: busy"}""")
        assertEquals(Phase.Live, live.uiState().connection.phase)
        val toast = live.uiState().toasts.single()
        assertEquals(ToastLevel.Error, toast.level)
        assertEquals("prompt failed: busy", toast.text)
    }

    @Test
    fun toastsAreCappedAndDismissable() {
        val r = reducer()
        r.feed(welcome(entryCount = 0))
        repeat(55) { r.feed(event("""{"type":"notice","level":"warning","message":"n$it"}""")) }
        r.feed(event("""{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":1000,"errorMessage":"overloaded"}"""))
        val toasts = r.uiState().toasts
        assertEquals(50, toasts.size)
        assertEquals("n6", toasts.first().text)
        assertEquals("retry 1/3: overloaded", toasts.last().text)
        r.dismissToast(toasts.first().id)
        assertEquals("n7", r.uiState().toasts.first().text)
    }

    @Test
    fun turnFinishedAndByeSignals() {
        val r = reducer()
        r.feed(welcome(entryCount = 0, streaming = true))
        r.feed("""{"t":"state","state":${state(false)}}""")
        assertEquals(SessionSignal.TurnFinished("Fix login"), effects.signals.last())
        assertFalse(r.uiState().header?.isStreaming ?: true)

        r.feed("""{"t":"bye","reason":"host stopped"}""")
        assertEquals(Phase.Ended, r.uiState().connection.phase)
        assertEquals(SessionSignal.Ended("host stopped"), effects.signals.last())
        assertTrue(effects.shutdown)
    }

    @Test
    fun agentProgressMergesAndIsPrunedAfterTwoUnlistedRosters() {
        val r = reducer()
        r.feed(welcome(entryCount = 0))
        val roster = """{"t":"agents","agents":[{"id":"0-Explore","displayName":"Explore","kind":"sub","parentId":"Main","status":"running","hasSessionFile":true,"createdAt":1,"lastActivity":2}]}"""
        val progress = """{"t":"bus","channel":"task:subagent:progress","data":{"index":0,"agent":"Explore","task":"scan","progress":""" +
            """{"index":0,"id":"0-Explore","agent":"Explore","status":"running","task":"scan","currentTool":"grep","recentTools":[],"recentOutput":["found 3"],"toolCount":4,"requests":2,"tokens":900,"cost":0.01,"durationMs":1200}}}"""
        r.feed(roster, progress)
        val agent = r.uiState().agents.single()
        assertEquals("grep", agent.progress?.currentTool)
        assertEquals(listOf("found 3"), agent.progress?.recentOutput)

        r.feed("""{"t":"agents","agents":[]}""", roster)
        assertEquals(4, r.uiState().agents.single().progress?.toolCount, "one unlisted roster keeps progress")
        r.feed("""{"t":"agents","agents":[]}""", """{"t":"agents","agents":[]}""", roster)
        assertNull(r.uiState().agents.single().progress, "two consecutive unlisted rosters prune it")
    }

    @Test
    fun v3HostMismatchSwitchesTheSessionToV3AndRejoins() {
        val r = reducer()
        r.downgradeToV3()
        assertEquals(1, effects.reconnects)
        assertEquals(Phase.Connecting, r.uiState().connection.phase)
        assertTrue(effects.signals.isEmpty(), "a supported mismatch is not a failure")
        assertEquals(
            Fixtures.json("""{"t":"hello","proto":3,"name":"pixel","writeToken":"oKGio6SlpqeoqaqrrK2urw"}"""),
            effects.sent.last(),
        )
        r.feed(v3Welcome(entryCount = 0))
        assertEquals(Phase.Live, r.uiState().connection.phase)
        // Later reconnects of this session keep speaking v3.
        r.onSocketLost("connection lost")
        r.onSocketOpen()
        assertEquals(3L, effects.sent.last().long("proto"))
    }

    @Test
    fun unsupportedHostVersionIsFatal() {
        val r = reducer()
        r.feed("""{"t":"error","message":"protocol mismatch: host speaks v6, guest sent v5"}""")
        assertEquals(Phase.Failed, r.uiState().connection.phase)
        assertEquals("This oms speaks collab v6; update oms remote", r.uiState().connection.message)
        assertEquals(0, effects.reconnects)
    }

    @Test
    fun v3MessageUpdatesBuildTheGhostUntilTheEntryLands() {
        val r = reducer()
        r.downgradeToV3()
        r.feed(v3Welcome(entryCount = 1), chunk(true, user("e1", "hi")))
        r.feed(event("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""))
        r.feed(event("""{"type":"message_update","message":{"role":"assistant","content":[{"type":"text","text":"Hel"}]}}"""))
        val ghostKey = keys(r).last()
        r.feed(event("""{"type":"message_update","message":{"role":"assistant","content":[{"type":"text","text":"Hello"}]}}"""))
        val ghost = assertIs<TranscriptItem.Assistant>(r.uiState().items.last())
        assertEquals(listOf("e1", ghostKey), keys(r))
        assertTrue(ghost.streaming)
        assertEquals(listOf(AssistantBlock.Text("Hello")), ghost.blocks)

        r.feed(event("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Hello"}],"stopReason":"stop"}}"""))
        r.feed(entryFrame(assistant("e2", """[{"type":"text","text":"Hello"}]""")))
        assertEquals(listOf("e1", "e2"), keys(r))
    }

    @Test
    fun v3SnapshotKeepsTheNewestEntriesAndNeverPages() {
        val r = reducer()
        r.downgradeToV3()
        val entries = (1..650).map { user("e$it", "m$it") }
        r.feed(v3Welcome(entryCount = 650), chunk(false, *entries.take(400).toTypedArray()), chunk(true, *entries.drop(400).toTypedArray()))
        val ui = r.uiState()
        assertEquals(EntryWindow.LIVE_CAP, ui.items.size)
        assertEquals("e51", ui.items.first().key)
        assertEquals("e650", ui.items.last().key)
        assertFalse(ui.hasOlder)
        r.loadOlder()
        assertTrue(effects.sent.none { it.str("t") == "fetch-history" })
    }

    private fun featuredWelcome(features: String = """["models"]""", readOnly: Boolean = false) =
        welcome(entryCount = 0, readOnly = readOnly).dropLast(1) + ""","features":$features}"""

    private fun commandsFrame() =
        """{"t":"commands","commands":[""" +
            """{"name":"effort","aliases":["think"],"hint":"[level]","subcommands":[{"name":"high","description":"More"}],"source":"builtin"},""" +
            """{"name":"explain","description":"Explain a file","source":"template"},{"source":"builtin"}]}"""

    private fun acks() = effects.sent.filter { it.str("t") == "snapshot-ack" }.map { it.long("chunks") }

    @Test
    fun v5SnapshotChunksAreAcknowledgedCumulativelyPerWelcome() {
        val r = reducer()
        r.feed(welcome(entryCount = 3), chunk(false, user("e1", "a")), chunk(false, user("e2", "b")))
        assertEquals(listOf(1L, 2L), acks(), "each chunk is acknowledged as it arrives, before the snapshot completes")
        assertEquals(Phase.Connecting, r.uiState().connection.phase)
        r.feed(chunk(true, user("e3", "c")))
        assertEquals(listOf(1L, 2L, 3L), acks())
        assertEquals(Phase.Live, r.uiState().connection.phase)

        // A new welcome restarts the count; an empty snapshot still has its one final chunk.
        r.onSocketLost("gone")
        r.onSocketOpen()
        effects.sent.clear()
        r.feed(welcome(entryCount = 0), chunk(true))
        assertEquals(listOf(1L), acks())

        // v4 and v3 hosts do not pace snapshots and do not know the frame.
        effects.sent.clear()
        val v4 = reducer()
        v4.downgradeTo(4)
        v4.feed(welcome(entryCount = 1).replace("\"proto\":5", "\"proto\":4"), chunk(true, user("e1", "a")))
        assertEquals(Phase.Live, v4.uiState().connection.phase)
        assertTrue(acks().isEmpty())
    }

    @Test
    fun commandsComeFromV5HostsToFullLinksOnly() {
        val v4 = reducer()
        v4.downgradeTo(4)
        v4.feed(welcome(entryCount = 0).replace("\"proto\":5", "\"proto\":4"))
        v4.runCommand("/compact")
        assertFalse(v4.uiState().controls.commands)
        assertTrue(effects.sent.none { it.str("t") == "command" }, "a v4 host would take the frame for garbage")

        val viewer = reducer(writable = false)
        viewer.feed(welcome(entryCount = 0, readOnly = true), commandsFrame())
        viewer.runCommand("/compact")
        assertNull(viewer.uiState().commands)
        assertTrue(effects.sent.none { it.str("t") == "command" })

        val r = reducer()
        r.feed(welcome(entryCount = 1), commandsFrame(), chunk(true, user("e1", "a")))
        assertTrue(r.uiState().controls.commands)
        val commands = r.uiState().commands.orEmpty()
        assertEquals(listOf("effort", "explain"), commands.map { it.name })
        assertEquals(listOf("think"), commands[0].aliases)
        assertEquals("high", commands[0].subcommands.single().name)
        assertTrue(commands[0].takesArgs)
        assertEquals("template", commands[1].source)

        // No list before the first chunk: the host has none to offer, so the palette stops waiting.
        val bare = reducer()
        bare.feed(welcome(entryCount = 1), chunk(true, user("e1", "a")))
        assertEquals(emptyList(), bare.uiState().commands)
    }

    @Test
    fun commandRunsCollectOutputAndSettleOnCommandEnd() {
        val r = reducer()
        r.feed(welcome(entryCount = 0))
        effects.sent.clear()

        r.runCommand("  /btw what changed? ")
        val sent = effects.sent.single()
        assertEquals(Fixtures.json("""{"t":"command","reqId":${sent.long("reqId")},"text":"/btw what changed?"}"""), sent)
        val reqId = sent.long("reqId")
        assertEquals(CommandRunStatus.Running, r.uiState().commandRuns.single().status)

        r.feed(
            """{"t":"command-output","reqId":$reqId,"text":"Two"}""",
            """{"t":"command-output","reqId":$reqId,"text":" files","delta":true}""",
            """{"t":"command-output","reqId":$reqId,"text":"Done."}""",
            """{"t":"command-output","reqId":999,"text":"someone else's"}""",
        )
        assertEquals("Two files\n\nDone.", r.uiState().commandRuns.single().output)

        r.cancelCommandRun(r.uiState().commandRuns.single().id)
        assertEquals(Fixtures.json("""{"t":"command-cancel","reqId":$reqId}"""), effects.sent.last())
        r.feed("""{"t":"command-end","reqId":$reqId,"status":"cancelled"}""")
        val cancelled = r.uiState().commandRuns.single()
        assertEquals(CommandRunStatus.Failed, cancelled.status)
        assertEquals("Cancelled", cancelled.error)
        assertEquals("Two files\n\nDone.", cancelled.output)

        r.runCommand("/skill:review the diff")
        r.feed("""{"t":"command-end","reqId":${effects.sent.last().long("reqId")},"status":"prompt"}""")
        assertEquals(CommandRunStatus.Done, r.uiState().commandRuns.last().status)

        r.runCommand("/mcp list")
        r.feed("""{"t":"command-end","reqId":${effects.sent.last().long("reqId")},"status":"error","message":"/mcp is host-only"}""")
        val refused = r.uiState().commandRuns.last()
        assertEquals(CommandRunStatus.Failed, refused.status)
        assertEquals("/mcp is host-only", refused.error)

        r.runCommand("/compact")
        r.onSocketLost("gone")
        val lost = r.uiState().commandRuns.last()
        assertEquals(CommandRunStatus.Failed, lost.status)
        assertEquals(SessionReducer.ERR_RUN_LOST, lost.error)
    }

    @Test
    fun completionsFollowTheLatestDraftOnly() {
        val r = reducer()
        r.feed(welcome(entryCount = 0))
        effects.sent.clear()

        r.complete("/switch @sm")
        r.complete("/switch @smo")
        r.complete("/switch @smo")
        val (first, second) = effects.sent.map { it.long("reqId") }
        assertEquals(listOf("/switch @sm", "/switch @smo"), effects.sent.map { it.str("text") })

        r.feed("""{"t":"completions","reqId":$first,"items":[{"value":"@smol","label":"@smol"}]}""")
        assertNull(r.uiState().completions, "a reply for an older draft is dropped")
        r.feed("""{"t":"completions","reqId":$second,"items":[{"value":"@smol","label":"@smol","description":"openai/gpt-5.2"},{"label":"no value"}]}""")
        val completions = assertNotNull(r.uiState().completions)
        assertEquals("/switch @smo", completions.text)
        assertEquals(listOf("@smol"), completions.items.map { it.value })
        assertEquals("openai/gpt-5.2", completions.items.single().description)

        // Only an argument has host completions: a bare command name clears them without asking.
        r.complete("/sw")
        assertNull(r.uiState().completions)
        assertEquals(2, effects.sent.size)
    }

    @Test
    fun modelControlsNeedTheModelsFeatureAndReloadTheCatalogAfterAChange() {
        val plain = reducer()
        plain.feed(welcome(entryCount = 0))
        plain.loadModels()
        plain.setModel("openai", "gpt")
        plain.setThinking("low")
        assertFalse(plain.uiState().controls.models)
        assertTrue(effects.sent.none { it.str("t") in setOf("fetch-models", "set-model", "set-thinking") })

        val r = reducer()
        r.feed(featuredWelcome())
        effects.sent.clear()
        r.loadModels()
        val fetch = effects.sent.single()
        assertEquals("fetch-models", fetch.str("t"))
        assertTrue(r.uiState().loadingModels)
        r.feed(
            """{"t":"models","reqId":${fetch.long("reqId")},"models":[{"provider":"anthropic","id":"claude-x","name":"Claude X","reasoning":true,"scoped":true},""" +
                """{"provider":"openai","id":"gpt","name":"GPT","reasoning":false}],"roles":[{"role":"smol","provider":"openai","id":"gpt"}],""" +
                """"current":{"provider":"anthropic","id":"claude-x"},"thinkingLevel":"high","thinkingLevels":["off","auto","low","high"]}""",
        )
        val catalog = assertNotNull(r.uiState().models)
        assertFalse(r.uiState().loadingModels)
        assertEquals("anthropic/claude-x", catalog.currentKey)
        assertEquals(listOf(true, false), catalog.models.map { it.scoped })
        assertEquals("smol", catalog.roles.single().role)

        effects.sent.clear()
        r.setModel("openai", "gpt")
        val set = effects.sent.single()
        assertEquals(Fixtures.json("""{"t":"set-model","reqId":${set.long("reqId")},"provider":"openai","id":"gpt"}"""), set)
        assertEquals("Model → GPT", r.uiState().commandRuns.last().label)
        r.feed("""{"t":"command-end","reqId":${set.long("reqId")},"status":"done"}""")
        assertEquals(CommandRunStatus.Done, r.uiState().commandRuns.last().status)
        val reload = effects.sent.last()
        assertEquals("fetch-models", reload.str("t"), "the catalog's current model is stale after a switch")

        // A refused catalog keeps the one already shown and says why.
        r.feed("""{"t":"models","reqId":${reload.long("reqId")},"models":[],"roles":[],"thinkingLevels":[],"error":"Model controls are unavailable"}""")
        assertEquals(catalog, r.uiState().models)
        assertEquals(ToastLevel.Warning, r.uiState().toasts.last().level)
        assertTrue(r.uiState().toasts.last().text.endsWith("Model controls are unavailable"))
    }

    private fun pickerCommandsFrame() =
        """{"t":"commands","commands":[{"name":"switch","hint":"[model]","source":"builtin"},""" +
            """{"name":"effort","aliases":["think"],"hint":"[level]","source":"builtin"}]}"""

    private fun stateFrame(modelId: String, thinking: String) =
        """{"t":"state","state":{"isStreaming":false,"queuedMessageCount":0,"cwd":"/w","participants":[],""" +
            """"model":{"provider":"anthropic","id":"$modelId","name":"$modelId","contextWindow":null},"thinkingLevel":"$thinking"}}"""

    private fun sentText(type: String) = effects.sent.filter { it.str("t") == type }.map { it.str("text") }

    @Test
    fun stockV5HostsGetTheModelSheetThroughSwitchAndEffortCommands() {
        // A v5 host that does not let this guest run /switch or /effort has no picker to offer.
        val bare = reducer()
        bare.feed(welcome(entryCount = 0), commandsFrame().replace("\"name\":\"effort\"", "\"name\":\"compact\"").replace("\"think\"", "\"zip\""))
        assertFalse(bare.uiState().controls.modelPicker)

        val r = reducer()
        r.feed(welcome(entryCount = 0), pickerCommandsFrame(), stateFrame("claude-x", "high"))
        assertTrue(r.uiState().controls.modelPicker)
        assertFalse(r.uiState().controls.models, "the native catalog stays a host feature")
        effects.sent.clear()

        r.loadModels()
        assertEquals(listOf("/switch ", "/switch @", "/effort "), sentText("complete"))
        assertEquals(listOf("/effort"), sentText("command"), "the current model's levels come from a bare /effort")
        assertTrue(r.uiState().loadingModels)
        val req = effects.sent.associate { (it.str("text") ?: "") to it.long("reqId") }

        r.feed(
            """{"t":"completions","reqId":${req["/switch "]},"items":[{"value":"anthropic/claude-x ","label":"anthropic/claude-x","description":"Claude X"},""" +
                """{"value":"ollama/qwen3:8b ","label":"ollama/qwen3:8b","description":"Qwen3 8B"}]}""",
            """{"t":"completions","reqId":${req["/switch @"]},"items":[{"value":"@smol ","label":"@smol","description":"ollama/qwen3:8b:low"},{"value":"@x ","label":"@x","description":"unset"}]}""",
            """{"t":"completions","reqId":${req["/effort "]},"items":[{"value":"off","label":"off"},{"value":"low","label":"low"},{"value":"high","label":"high"},{"value":"max","label":"max"}]}""",
            """{"t":"command-output","reqId":${req["/effort"]},"text":"Thinking: high\nAvailable: off, low, high"}""",
            """{"t":"command-end","reqId":${req["/effort"]},"status":"done"}""",
        )
        val catalog = assertNotNull(r.uiState().models)
        assertFalse(r.uiState().loadingModels)
        assertTrue(catalog.searchOnHost)
        assertEquals(listOf("anthropic/claude-x", "ollama/qwen3:8b"), catalog.models.map { it.key }, "host rank order, ids with a colon intact")
        assertEquals("Qwen3 8B", catalog.models[1].name)
        assertEquals("anthropic/claude-x", catalog.currentKey)
        assertEquals("high", catalog.thinkingLevel)
        assertEquals(listOf("off", "low", "high"), catalog.thinkingLevels, "the probe narrows the full list to what this model takes")
        val role = catalog.roles.single()
        assertEquals(listOf("smol", "ollama", "qwen3:8b", "low"), listOf(role.role, role.provider, role.id, role.thinkingLevel))
        assertTrue(r.uiState().commandRuns.isEmpty(), "the probe is not shown as a run")

        effects.sent.clear()
        r.setModel("ollama", "qwen3:8b")
        val switch = effects.sent.single()
        assertEquals(Fixtures.json("""{"t":"command","reqId":${switch.long("reqId")},"text":"/switch ollama/qwen3:8b"}"""), switch)
        assertEquals("Model → Qwen3 8B", r.uiState().commandRuns.last().label)
        r.feed("""{"t":"command-end","reqId":${switch.long("reqId")},"status":"done"}""")
        assertEquals(CommandRunStatus.Done, r.uiState().commandRuns.last().status)
        assertEquals(listOf("/switch ollama/qwen3:8b", "/effort"), sentText("command"), "a switch re-reads the new model's levels")

        // A model without thinking levels hides the chips; unparseable output keeps the last list.
        val probe = effects.sent.last().long("reqId")
        r.feed(
            """{"t":"command-output","reqId":$probe,"text":"ollama/qwen3:8b has no adjustable thinking level."}""",
            """{"t":"command-end","reqId":$probe,"status":"done"}""",
        )
        assertEquals(emptyList(), r.uiState().models?.thinkingLevels)

        effects.sent.clear()
        r.setThinking("low")
        assertEquals(listOf("/effort low"), sentText("command"))

        // Search goes to the host; an answer for an older query is dropped.
        effects.sent.clear()
        r.searchModels("qw")
        r.searchModels("qwen")
        assertEquals(listOf("/switch qw", "/switch qwen"), sentText("complete"))
        val (older, newer) = effects.sent.map { it.long("reqId") }
        r.feed("""{"t":"completions","reqId":$older,"items":[{"value":"x/old ","label":"x/old"}]}""")
        r.feed("""{"t":"completions","reqId":$newer,"items":[{"value":"ollama/qwen3:8b ","label":"ollama/qwen3:8b","description":"Qwen3 8B"}]}""")
        assertEquals(listOf("ollama/qwen3:8b"), r.uiState().models?.models?.map { it.key })
    }

    @Test
    fun thinkingProbeOutputParsing() {
        assertEquals(listOf("off", "auto", "high"), SessionReducer.parseThinkingLevels("Thinking: model default\nAvailable: off, auto, high"))
        assertEquals(emptyList(), SessionReducer.parseThinkingLevels("The current model has no adjustable thinking level."))
        assertNull(SessionReducer.parseThinkingLevels("Usage: /effort [level]"))
    }

    private val rewindCommands =
        """{"t":"commands","commands":[{"name":"branch","aliases":["rewind"],"hint":"[n|entry id]","source":"builtin"}]}"""

    @Test
    fun rewindRunsOnTheHostAndHandsBackThePromptItWentPast() {
        val plain = reducer()
        plain.feed(welcome(entryCount = 0), commandsFrame())
        plain.rewind("e3")
        assertFalse(plain.uiState().controls.rewind)
        assertTrue(effects.sent.none { it.str("t") == "command" }, "a host that does not offer /rewind is not asked")

        val r = reducer()
        r.feed(welcome(entryCount = 0), rewindCommands)
        assertTrue(r.uiState().controls.rewind)
        effects.sent.clear()
        r.rewind("e3")
        val sent = effects.sent.single()
        assertEquals(Fixtures.json("""{"t":"command","reqId":${sent.long("reqId")},"text":"/rewind e3"}"""), sent)

        // The status block, then the prompt verbatim as the last block (it may arrive in deltas).
        r.feed(
            """{"t":"command-output","reqId":${sent.long("reqId")},"text":"Rewound 1 turn; the old path is kept as a branch."}""",
            """{"t":"command-output","reqId":${sent.long("reqId")},"text":"fix the login"}""",
            """{"t":"command-output","reqId":${sent.long("reqId")},"text":"\n\nand add a test","delta":true}""",
        )
        assertNull(r.uiState().rewoundPrompt, "the prompt is handed back only once the rewind succeeded")
        r.feed("""{"t":"command-end","reqId":${sent.long("reqId")},"status":"done"}""")
        val prompt = assertNotNull(r.uiState().rewoundPrompt)
        assertEquals("fix the login\n\nand add a test", prompt.text)
        r.consumeRewoundPrompt(prompt.id)
        assertNull(r.uiState().rewoundPrompt)

        // A refused rewind (agent busy) hands nothing back.
        effects.sent.clear()
        r.rewind("e1")
        val refused = effects.sent.single().long("reqId")
        r.feed(
            """{"t":"command-output","reqId":$refused,"text":"Wait for the current response to finish."}""",
            """{"t":"command-end","reqId":$refused,"status":"error"}""",
        )
        assertNull(r.uiState().rewoundPrompt)
    }

    @Test
    fun midConnectionWelcomeReplacesTheTranscriptWithTheHostsNewBranch() {
        val r = reducer()
        r.feed(
            welcome(entryCount = 4),
            chunk(true, user("e1", "a"), assistant("e2", """[{"type":"text","text":"b"}]"""), user("e3", "c"), assistant("e4", """[{"type":"text","text":"d"}]""")),
            uiRequest(5),
        )
        assertEquals(listOf("e1", "e2", "e3", "e4"), keys(r))
        effects.sent.clear()

        // A rewind on the host: the same socket gets a fresh welcome holding the shorter branch.
        r.feed(welcome(entryCount = 2), chunk(false, user("e1", "a")))
        assertEquals(listOf("e1", "e2", "e3", "e4"), keys(r), "the old view stays until the new snapshot is complete")
        assertEquals(Phase.Live, r.uiState().connection.phase)
        r.feed(chunk(true, assistant("e2", """[{"type":"text","text":"b"}]""")), uiRequest(5))
        assertEquals(listOf("e1", "e2"), keys(r))
        assertEquals(listOf(1L, 2L), acks(), "the resync's chunks are acknowledged from 1 again")
        // The pending question the host replayed after the welcome is the same one: not resolved, not re-notified.
        assertEquals(listOf(5L), r.uiState().pending.map { it.reqId })
        r.feed("""{"t":"state","state":${state(false)}}""")
        assertTrue(effects.signals.none { it is SessionSignal.RequestResolved })
        assertEquals(1, effects.signals.count { it is SessionSignal.NeedsInput })
    }
}
