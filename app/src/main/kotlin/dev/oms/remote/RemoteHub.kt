package dev.oms.remote

import android.content.Context
import android.os.Build
import android.util.Log
import dev.oms.remote.data.LinkStore
import dev.oms.remote.data.SavedHost
import dev.oms.remote.pairing.LinkText
import dev.oms.remote.protocol.api.CollabSessions
import dev.oms.remote.protocol.api.InvalidCollabLinkException
import dev.oms.remote.protocol.api.Phase
import dev.oms.remote.protocol.api.RemoteSession
import dev.oms.remote.service.CollabService
import java.security.GeneralSecurityException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A user-readable failure from [RemoteHub]. */
class RemoteHubException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * App-wide owner of the saved hosts and the (single) live session. Initialized by [OmsRemoteApp];
 * all members are thread-safe. At most one session exists; connecting replaces it.
 */
object RemoteHub {
    private lateinit var appContext: Context
    private lateinit var scope: CoroutineScope
    private lateinit var store: LinkStore

    private val lock = Any()
    private val _activeHostId = MutableStateFlow<String?>(null)
    private val _session = MutableStateFlow<RemoteSession?>(null)

    /** The host [session] belongs to, even after the session ended (until [disconnect]). */
    private var sessionHostId: String? = null
    private var terminalWatch: Job? = null

    val hosts: StateFlow<List<SavedHost>> get() = store.hosts

    /** The host with a live (not ended/failed) session, or null. */
    val activeHostId: StateFlow<String?> = _activeHostId.asStateFlow()

    /** The current session. It stays set after it ends or fails so the UI can show why; [disconnect] clears it. */
    val session: StateFlow<RemoteSession?> = _session.asStateFlow()

    internal fun init(context: Context, appScope: CoroutineScope) {
        appContext = context.applicationContext
        scope = appScope
        store = LinkStore(appContext)
    }

    /**
     * Validates and saves a link found in [text] (see [LinkText.extract]). A link for an already
     * saved room replaces that host's link. A blank [name] keeps the old name or uses the relay host.
     */
    fun addLink(text: String, name: String): Result<SavedHost> {
        if (text.isBlank()) return failure(appContext.getString(R.string.error_empty_link))
        val link = try {
            LinkText.extract(text)
        } catch (e: InvalidCollabLinkException) {
            return Result.failure(e)
        } catch (e: IllegalArgumentException) {
            return failure(appContext.getString(R.string.error_invalid_link), e)
        }
        return try {
            Result.success(store.upsert(link, name))
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Sealing a link failed (${e.javaClass.simpleName})")
            failure(appContext.getString(R.string.error_link_store_failed), e)
        }
    }

    fun rename(hostId: String, name: String) = store.rename(hostId, name)

    /** Forgets a host, disconnecting first if the current session belongs to it. */
    fun delete(hostId: String) {
        if (synchronized(lock) { sessionHostId == hostId }) disconnect()
        store.delete(hostId)
    }

    /**
     * Closes any current session and opens a new one to [hostId], then starts the foreground
     * service that keeps it alive and posts notifications. Call from the foreground (UI): Android
     * refuses to start foreground services from the background.
     */
    fun connect(hostId: String): Result<RemoteSession> {
        val host = store[hostId] ?: return failure(appContext.getString(R.string.error_host_missing))
        val link = try {
            CollabSessions.parseLink(store.rawLink(host))
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Unsealing a saved link failed (${e.javaClass.simpleName})")
            return failure(appContext.getString(R.string.error_link_unreadable), e)
        } catch (e: IllegalArgumentException) {
            return failure(appContext.getString(R.string.error_link_unreadable), e)
        }
        val session = CollabSessions.open(link, deviceName = Build.MODEL, scope = scope)
        replaceSession(session, hostId)
        session.start()
        store.markConnected(hostId, System.currentTimeMillis())
        CollabService.start(appContext)
        return Result.success(session)
    }

    /** Leaves the room (if any) and forgets the session. The service stops on its own. */
    fun disconnect() = replaceSession(null, null)

    /**
     * (Re)starts the foreground service for a live session. Idempotent; called when the UI becomes
     * visible in case the start in [connect] was refused (Android blocks background FGS starts).
     */
    fun ensureService() {
        val phase = _session.value?.state?.value?.connection?.phase ?: return
        if (phase != Phase.Ended && phase != Phase.Failed) CollabService.start(appContext)
    }

    private fun replaceSession(next: RemoteSession?, hostId: String?) {
        val previous = synchronized(lock) {
            val previous = _session.value
            terminalWatch?.cancel()
            // Publish the new session before closing the old one: observers ignore the old one's end.
            _session.value = next
            _activeHostId.value = hostId
            sessionHostId = hostId
            terminalWatch = next?.let(::watchTerminal)
            previous
        }
        if (previous != null && previous !== next) previous.close()
    }

    /** Once [session] ends or fails, its host is no longer "active" (Home offers Connect again). */
    private fun watchTerminal(session: RemoteSession): Job = scope.launch {
        session.state.first { it.connection.phase == Phase.Ended || it.connection.phase == Phase.Failed }
        synchronized(lock) {
            if (_session.value === session) _activeHostId.value = null
        }
    }

    private fun <T> failure(message: String, cause: Throwable? = null): Result<T> =
        Result.failure(RemoteHubException(message, cause))

    private const val TAG = "RemoteHub"
}
