package dev.oms.remote.protocol

import dev.oms.remote.protocol.api.Phase
import dev.oms.remote.protocol.api.SessionUiState
import dev.oms.remote.protocol.api.TranscriptItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** MockWebServer plays relay and host: it decrypts the guest's frames and answers with sealed host frames. */
class CollabSessionEndToEndTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hostCipher = RoomCipher(Fixtures.KEY)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    /** One relay room instance: records the guest's frames and lets [onFrame] answer each one. */
    private inner class HostSide(private val onFrame: (WebSocket, JsonObject) -> Unit) : WebSocketListener() {
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        @Volatile var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            socket = webSocket
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val envelope = requireNotNull(Envelope.unpack(bytes.toByteArray()))
            val frame = Fixtures.json(hostCipher.open(envelope.sealed).toString(Charsets.UTF_8))
            frames.trySend(frame)
            onFrame(webSocket, frame)
        }
    }

    private fun WebSocket.sendHostFrame(json: String) {
        send(Envelope.pack(hostCipher.seal(json.toByteArray(Charsets.UTF_8)), peerId = 1).toByteString())
    }

    private val welcome =
        """{"t":"welcome","proto":5,"header":{"type":"session","id":"s1","title":"Fix login"},""" +
            """"state":{"isStreaming":false,"isAborting":false,"queuedMessageCount":0,"participants":[{"name":"alice","role":"host"}]},""" +
            """"agents":[],"entryCount":2,"history":{"beforeId":null,"afterId":null,"total":2}}"""

    private val firstEntry = """{"type":"message","id":"e1","parentId":null,"timestamp":"t","message":{"role":"user","content":"deploy it"}}"""
    private val secondEntry =
        """{"type":"message","id":"e2","parentId":"e1","timestamp":"t","message":{"role":"assistant","content":[{"type":"text","text":"Deploying"}],"stopReason":"stop"}}"""
    private val snapshot = """{"t":"snapshot-chunk","final":true,"entries":[$firstEntry,$secondEntry]}"""

    /**
     * A v5 room that paces the snapshot as `CollabHost` does: the second chunk is sent only once
     * the guest acknowledged the first, so a guest that never acks never finishes joining.
     */
    private fun pacedRoom(afterSnapshot: (WebSocket) -> Unit = {}) = HostSide { ws, frame ->
        when (frame.str("t")) {
            "hello" -> {
                ws.sendHostFrame(welcome)
                ws.sendHostFrame("""{"t":"snapshot-chunk","final":false,"entries":[$firstEntry]}""")
            }
            "snapshot-ack" -> if (frame.long("chunks") == 1L) {
                ws.sendHostFrame("""{"t":"snapshot-chunk","final":true,"entries":[$secondEntry]}""")
                afterSnapshot(ws)
            }
        }
    }

    private fun ack(chunks: Int) = Fixtures.json("""{"t":"snapshot-ack","chunks":$chunks}""")

    private val approval =
        """{"t":"ui-request","request":{"reqId":4,"kind":"select","title":"Allow tool: bash\nReason: deploy","options":["Approve","Deny"]}}"""

    @Test
    fun joinsRendersSnapshotAndRejoinsAfterRoomClosed() = runBlocking {
        val firstRoom = pacedRoom { ws -> ws.sendHostFrame(approval) }
        val secondRoom = pacedRoom()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(firstRoom).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(secondRoom).build())
        server.start()

        val link = Fixtures.link(roomUrl = "ws://${server.hostName}:${server.port}/r/${Fixtures.ROOM_ID}")
        val session = CollabSession(link, "Pixel", scope, OkHttpClient())
        val phases = Collections.synchronizedList(ArrayList<Phase>())
        scope.launch { session.state.collect { phases += it.connection.phase } }
        session.start()

        val expectedHello = Fixtures.json("""{"t":"hello","proto":5,"name":"Pixel","history":"paged","writeToken":"oKGio6SlpqeoqaqrrK2urw"}""")
        assertEquals(expectedHello, withTimeout(TIMEOUT_MS) { firstRoom.frames.receive() })
        // Every chunk is acknowledged, the final one included.
        assertEquals(ack(1), withTimeout(TIMEOUT_MS) { firstRoom.frames.receive() })
        assertEquals(ack(2), withTimeout(TIMEOUT_MS) { firstRoom.frames.receive() })
        val request = server.takeRequest()
        assertEquals("/r/${Fixtures.ROOM_ID}", request.url.encodedPath)
        assertEquals("guest", request.url.queryParameter("role"))

        val live = session.awaitState { it.connection.phase == Phase.Live && it.pending.isNotEmpty() }
        assertEquals("Fix login", live.header?.title)
        assertEquals(listOf("e1", "e2"), live.items.map { it.key })
        assertEquals("deploy it", assertIs<TranscriptItem.User>(live.items[0]).text)
        assertEquals("bash", live.pending.single().approvalTool)

        session.respond(4, "Approve")
        assertEquals(Fixtures.json("""{"t":"ui-response","reqId":4,"value":"Approve"}"""), withTimeout(TIMEOUT_MS) { firstRoom.frames.receive() })

        requireNotNull(firstRoom.socket).close(4001, "room closed")
        assertEquals(expectedHello, withTimeout(TIMEOUT_MS) { secondRoom.frames.receive() }, "every reconnect starts with a fresh hello")
        assertEquals(ack(1), withTimeout(TIMEOUT_MS) { secondRoom.frames.receive() }, "the chunk count restarts with each welcome")
        val rejoined = session.awaitState { it.connection.phase == Phase.Live && it.pending.isEmpty() }
        assertEquals(listOf("e1", "e2"), rejoined.items.map { it.key })
        assertTrue(Phase.Reconnecting in phases.toList(), "phases seen: $phases")

        session.close()
        assertEquals(Phase.Ended, session.awaitState { it.connection.phase == Phase.Ended }.connection.phase)
    }

    /** An oms 18.11 host (collab v3) refuses the v5 hello; the guest rejoins at once speaking v3. */
    @Test
    fun negotiatesDownToAV3Host() = runBlocking {
        val refusingRoom = HostSide { ws, frame ->
            if (frame.str("t") == "hello") {
                ws.sendHostFrame("""{"t":"error","message":"protocol mismatch: host speaks v3, guest sent v5"}""")
            }
        }
        val v3Room = HostSide { ws, frame ->
            if (frame.str("t") != "hello") return@HostSide
            ws.sendHostFrame(
                """{"t":"welcome","proto":3,"header":{"type":"session","id":"s1","title":"Fix login"},""" +
                    """"state":{"isStreaming":false,"participants":[]},"agents":[],"entryCount":2}""",
            )
            ws.sendHostFrame(snapshot)
        }
        server.enqueue(MockResponse.Builder().webSocketUpgrade(refusingRoom).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(v3Room).build())
        server.start()

        val link = Fixtures.link(roomUrl = "ws://${server.hostName}:${server.port}/r/${Fixtures.ROOM_ID}")
        val session = CollabSession(link, "Pixel", scope, OkHttpClient())
        val phases = Collections.synchronizedList(ArrayList<Phase>())
        scope.launch { session.state.collect { phases += it.connection.phase } }
        session.start()

        assertEquals(5L, withTimeout(TIMEOUT_MS) { refusingRoom.frames.receive() }.long("proto"))
        assertEquals(
            Fixtures.json("""{"t":"hello","proto":3,"name":"Pixel","writeToken":"oKGio6SlpqeoqaqrrK2urw"}"""),
            withTimeout(TIMEOUT_MS) { v3Room.frames.receive() },
        )
        val live = session.awaitState { it.connection.phase == Phase.Live }
        assertEquals(listOf("e1", "e2"), live.items.map { it.key })
        assertFalse(live.hasOlder)
        assertTrue(Phase.Reconnecting !in phases.toList() && Phase.Failed !in phases.toList(), "phases seen: $phases")
    }

    @Test
    fun missingRoomOnFirstJoinIsFatal() = runBlocking {
        val room = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.close(4004, "no such room")
            }
        }
        val failed = joinAndAwaitFailure(room)
        assertEquals(CollabSession.ERR_NO_ROOM, failed.connection.message)
    }

    @Test
    fun undecryptableFrameIsFatal() = runBlocking {
        val wrongKey = RoomCipher(ByteArray(32))
        val room = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val sealed = wrongKey.seal("""{"t":"bye","reason":"x"}""".toByteArray())
                webSocket.send(Envelope.pack(sealed, peerId = 1).toByteString())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }
        val failed = joinAndAwaitFailure(room)
        assertEquals(CollabSession.ERR_DECRYPT, failed.connection.message)
    }

    /** Joins a room served by [room] and waits for [Phase.Failed]; asserts no reconnect was attempted. */
    private suspend fun joinAndAwaitFailure(room: WebSocketListener): SessionUiState {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(room).build())
        server.start()
        val link = Fixtures.link(roomUrl = "ws://${server.hostName}:${server.port}/r/${Fixtures.ROOM_ID}")
        val session = CollabSession(link, "Pixel", scope, OkHttpClient())
        session.start()
        val failed = session.awaitState { it.connection.phase == Phase.Failed }
        delay(FIRST_RETRY_MAX_MS + 250)
        assertEquals(1, server.requestCount, "a fatal close must not reconnect")
        return failed
    }

    private suspend fun CollabSession.awaitState(predicate: (SessionUiState) -> Boolean): SessionUiState =
        withTimeout(TIMEOUT_MS) { state.first(predicate) }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val FIRST_RETRY_MAX_MS = 1_250L
    }
}
