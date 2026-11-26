package dev.oms.remote.protocol.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/** A parsed collab link. [key] is 32 bytes; [writeToken] is 16 bytes or null for view-only links. */
class CollabLink(
    /** WebSocket URL of the room without query, e.g. `wss://relay.example/r/AbCdEf1234`. */
    val roomUrl: String,
    val roomId: String,
    val key: ByteArray,
    val writeToken: ByteArray?,
    /** The original text, trimmed, for storage and display. */
    val raw: String,
) {
    val canWrite: Boolean get() = writeToken != null

    /** Host part of [roomUrl] for display (e.g. `laptop.tail1234.ts.net`). */
    val relayHost: String get() = roomUrl.substringAfter("://").substringBefore('/')
}

class InvalidCollabLinkException(message: String) : IllegalArgumentException(message)

/** A live connection to one collab room. Create with [CollabSessions.open]. */
interface RemoteSession : RemoteActions {
    val link: CollabLink
    val state: StateFlow<SessionUiState>
    val signals: SharedFlow<SessionSignal>

    /** Open the socket and join. Idempotent. */
    fun start()

    /** Leave the room and stop reconnecting. Terminal: state becomes [Phase.Ended]. */
    fun close()
}

object CollabSessions {
    /**
     * Parse any accepted link form (spec section 1): bare `<roomId>.<secret>`, `host/r/<id>.<secret>`,
     * ws/wss/http/https URLs, and browser links `https://web/#<inner link>`.
     * @throws InvalidCollabLinkException with a user-readable message.
     */
    fun parseLink(input: String): CollabLink = dev.oms.remote.protocol.CollabLinks.parse(input)

    /**
     * Create a session. Frames are processed on [scope]; [deviceName] is sent as `hello.name`.
     * Call [RemoteSession.start] to connect.
     */
    fun open(
        link: CollabLink,
        deviceName: String,
        scope: CoroutineScope,
        client: OkHttpClient = defaultClient,
    ): RemoteSession = dev.oms.remote.protocol.CollabSession(link, deviceName, scope, client)

    /** Shared client with a 15 s WebSocket ping (spec 2.5). */
    val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(java.time.Duration.ofSeconds(15))
            .build()
    }
}
