package dev.oms.remote.protocol

import dev.oms.remote.protocol.api.AgentCommand
import dev.oms.remote.protocol.api.CollabLink
import dev.oms.remote.protocol.api.RemoteSession
import dev.oms.remote.protocol.api.SessionSignal
import dev.oms.remote.protocol.api.SessionUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * One guest connection to a collab room (spec 2, 4).
 *
 * Every input (socket callbacks, timers, user actions) goes through one unbounded channel that a
 * single coroutine drains in order, so OkHttp's reader thread only enqueues and keeps reading
 * (spec 9.10) and the [SessionReducer] needs no locking. The actor runs in [scope] on [context]
 * (default [Dispatchers.Default], keeping JSON work off the main thread).
 */
internal class CollabSession(
    override val link: CollabLink,
    private val deviceName: String,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val context: CoroutineContext = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : RemoteSession {
    private sealed interface Input {
        data object Start : Input

        data object Close : Input

        data object RetryNow : Input

        /** Reopen immediately, without backoff (protocol downgrade). */
        data object Reconnect : Input

        class Opened(val gen: Int) : Input

        class Binary(val gen: Int, val bytes: ByteArray) : Input

        class Closed(val gen: Int, val code: Int, val reason: String) : Input

        class RetryDue(val token: Long) : Input

        class TimerFired(val timer: SessionTimer, val token: Long) : Input

        class Action(val block: SessionReducer.() -> Unit) : Input
    }

    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)
    private val cipher = RoomCipher(link.key)
    private val outbox = ArrayList<SessionSignal>()
    private val reducer = SessionReducer(link, deviceName, Effects(), clock)

    private val _state = MutableStateFlow(reducer.uiState())
    override val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val _signals = MutableSharedFlow<SessionSignal>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val signals: SharedFlow<SessionSignal> = _signals.asSharedFlow()

    // Actor-confined connection state.
    @Volatile private var socket: WebSocket? = null
    private var socketOpen = false
    private var generation = 0
    private var attempt = 0
    private var retryMissingRoom = false
    private var retryJob: Job? = null
    private var retryToken = 0L
    private var terminated = false
    private val timerJobs = HashMap<SessionTimer, Job>()

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        val actor = scope.launch(context) {
            handle(Input.Start)
            for (input in inputs) {
                handle(input)
                if (terminated) break
            }
        }
        actor.invokeOnCompletion {
            inputs.close()
            socket?.cancel()
        }
    }

    override fun close() {
        if (started.compareAndSet(false, true)) {
            // Never started: no actor will ever run, so finish synchronously.
            inputs.close()
            handle(Input.Close)
            return
        }
        inputs.trySend(Input.Close)
    }

    override fun sendPrompt(text: String) = post { sendPrompt(text) }

    override fun abort() = post { abort() }

    override fun respond(reqId: Long, value: String?) = post { respond(reqId, value) }

    override fun agentCommand(agentId: String, command: AgentCommand, text: String?) = post { agentCommand(agentId, command, text) }

    override fun loadOlder() = post { loadOlder() }

    override fun dismissToast(id: Long) {
        // After the session ends the actor is gone; toasts can still be dismissed.
        if (inputs.trySend(Input.Action { dismissToast(id) }).isFailure) {
            _state.update { current -> current.copy(toasts = current.toasts.filterNot { it.id == id }) }
        }
    }

    override fun runCommand(text: String) = post { runCommand(text) }

    override fun cancelCommandRun(id: Long) = post { cancelCommandRun(id) }

    override fun complete(text: String) = post { complete(text) }

    override fun loadModels() = post { loadModels() }

    override fun searchModels(query: String) = post { searchModels(query) }

    override fun setModel(provider: String, id: String) = post { setModel(provider, id) }

    override fun setThinking(level: String) = post { setThinking(level) }

    override fun dismissCommandRun(id: Long) {
        if (inputs.trySend(Input.Action { dismissCommandRun(id) }).isFailure) {
            _state.update { current -> current.copy(commandRuns = current.commandRuns.filterNot { it.id == id }) }
        }
    }

    override fun rewind(entryId: String) = post { rewind(entryId) }

    override fun consumeRewoundPrompt(id: Long) {
        if (inputs.trySend(Input.Action { consumeRewoundPrompt(id) }).isFailure) {
            _state.update { current -> if (current.rewoundPrompt?.id == id) current.copy(rewoundPrompt = null) else current }
        }
    }

    override fun reconnectNow() {
        inputs.trySend(Input.RetryNow)
    }

    private fun post(action: SessionReducer.() -> Unit) {
        inputs.trySend(Input.Action(action))
    }

    // ── Actor ───────────────────────────────────────────────────────────────────────────────

    private fun handle(input: Input) {
        when (input) {
            Input.Start -> {
                reducer.start()
                connect()
            }
            Input.Close -> reducer.end(REASON_CLOSED)
            Input.RetryNow -> if (!terminated && socket == null && retryJob != null) connect()
            Input.Reconnect -> if (!terminated && socket == null) connect()
            is Input.Opened -> onOpened(input)
            is Input.Binary -> onBinary(input)
            is Input.Closed -> onClosed(input)
            is Input.RetryDue -> if (input.token == retryToken && !terminated && socket == null) connect()
            is Input.TimerFired -> {
                timerJobs.remove(input.timer)
                reducer.onTimer(input.timer, input.token)
            }
            is Input.Action -> input.block(reducer)
        }
        publish()
    }

    /** State first, then the signals it caused, so observers reacting to a signal see the new state. */
    private fun publish() {
        _state.value = reducer.uiState()
        if (outbox.isEmpty()) return
        val signals = outbox.toList()
        outbox.clear()
        signals.forEach(_signals::tryEmit)
    }

    private fun connect() {
        retryJob?.cancel()
        retryJob = null
        retryToken++
        val gen = ++generation
        socketOpen = false
        reducer.onConnecting()
        socket = try {
            client.newWebSocket(Request.Builder().url("${link.roomUrl}?role=guest").build(), Listener(gen))
        } catch (_: IllegalArgumentException) {
            reducer.fail(ERR_BAD_URL)
            null
        }
    }

    private fun onOpened(input: Input.Opened) {
        if (input.gen != generation || terminated) return
        socketOpen = true
        if (!retryMissingRoom) attempt = 0
        reducer.onSocketOpen()
    }

    private fun onBinary(input: Input.Binary) {
        if (input.gen != generation || terminated) return
        val envelope = Envelope.unpack(input.bytes) ?: return
        val plaintext = try {
            cipher.open(envelope.sealed)
        } catch (_: GeneralSecurityException) {
            reducer.fail(ERR_DECRYPT)
            return
        }
        retryMissingRoom = false
        attempt = 0
        val frame = HostFrames.decode(plaintext.toString(Charsets.UTF_8))
        try {
            reducer.onFrame(frame)
        } catch (_: RuntimeException) {
            // A malformed payload must not kill the actor (and with it the caller's scope).
            reducer.onApplyError(frame)
        }
    }

    /**
     * Close-code policy of spec 2.4. A 4004 is retried after a 4001 or once the guest has joined
     * (the host usually returns to the same room); on the first join it is fatal.
     */
    private fun onClosed(input: Input.Closed) {
        if (input.gen != generation || terminated) return
        generation++ // the rest of this socket's callbacks are stale
        socket = null
        socketOpen = false
        val code = input.code
        when {
            code == CLOSE_ROOM_CLOSED || (code == CLOSE_NO_ROOM && (retryMissingRoom || reducer.hasJoined)) -> {
                retryMissingRoom = true
                reducer.onSocketLost(REASON_HOST_AWAY)
                scheduleRetry()
            }
            code == CLOSE_NO_ROOM -> reducer.fail(ERR_NO_ROOM)
            code == CLOSE_ROOM_FULL -> reducer.fail(ERR_ROOM_FULL)
            code == CLOSE_HOST_TAKEN -> reducer.fail(ERR_HOST_TAKEN)
            else -> {
                reducer.onSocketLost(input.reason.takeIf(String::isNotBlank)?.let { "Connection lost: $it" } ?: "Connection lost (code $code)")
                scheduleRetry()
            }
        }
    }

    private fun scheduleRetry() {
        val base = minOf(BACKOFF_BASE_MS shl minOf(attempt, 15), BACKOFF_MAX_MS)
        attempt++
        val delayMs = (base * (0.75 + random.nextDouble() * 0.5)).toLong()
        reducer.onRetryScheduled(attempt, clock() + delayMs)
        val token = ++retryToken
        retryJob = scope.launch(context) {
            delay(delayMs)
            inputs.trySend(Input.RetryDue(token))
        }
    }

    private fun closeSocket() {
        generation++
        socketOpen = false
        socket?.close(1000, null)
        socket = null
    }

    private inner class Effects : SessionEffects {
        override fun send(frame: JsonObject) {
            val ws = socket ?: return
            if (!socketOpen) return
            ws.send(Envelope.pack(cipher.seal(GuestFrames.encode(frame))).toByteString())
        }

        override fun signal(signal: SessionSignal) {
            outbox += signal
        }

        override fun schedule(timer: SessionTimer, token: Long, delayMs: Long) {
            timerJobs.remove(timer)?.cancel()
            timerJobs[timer] = scope.launch(context) {
                delay(delayMs)
                inputs.trySend(Input.TimerFired(timer, token))
            }
        }

        override fun cancel(timer: SessionTimer) {
            timerJobs.remove(timer)?.cancel()
        }

        override fun reconnect() {
            // Stale the current socket now so none of its remaining frames are applied; the
            // reconnect itself runs as the next input, outside the reducer call.
            retryJob?.cancel()
            retryJob = null
            closeSocket()
            inputs.trySend(Input.Reconnect)
        }

        override fun shutdown() {
            terminated = true
            retryJob?.cancel()
            retryJob = null
            timerJobs.values.forEach { it.cancel() }
            timerJobs.clear()
            closeSocket()
        }
    }

    /** Runs on OkHttp's threads: only enqueue, never block. */
    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            inputs.trySend(Input.Opened(gen))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            inputs.trySend(Input.Binary(gen, bytes.toByteArray()))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Relay control (`room-closed`) is always followed by close 4001, which drives retry.
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            inputs.trySend(Input.Closed(gen, code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            inputs.trySend(Input.Closed(gen, code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            inputs.trySend(Input.Closed(gen, CLOSE_ABNORMAL, t.message.orEmpty()))
        }
    }

    companion object {
        private const val BACKOFF_BASE_MS = 1_000L
        private const val BACKOFF_MAX_MS = 30_000L
        private const val CLOSE_ABNORMAL = 1006
        private const val CLOSE_ROOM_CLOSED = 4001
        private const val CLOSE_NO_ROOM = 4004
        private const val CLOSE_HOST_TAKEN = 4009
        private const val CLOSE_ROOM_FULL = 4029

        const val REASON_CLOSED = "Disconnected"
        const val REASON_HOST_AWAY = "The host disconnected; waiting for it to come back"
        const val ERR_DECRYPT = "Could not decrypt a frame from the host (wrong key or corrupted data)"
        const val ERR_NO_ROOM = "No such room: the host is not sharing this session (the link may be stale)"
        const val ERR_ROOM_FULL = "The room is full"
        const val ERR_HOST_TAKEN = "The relay refused the connection: a host is already connected"
        const val ERR_BAD_URL = "The link's relay address is not a valid URL"
    }
}
