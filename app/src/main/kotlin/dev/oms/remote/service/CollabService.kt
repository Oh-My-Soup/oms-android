package dev.oms.remote.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.oms.remote.R
import dev.oms.remote.RemoteHub
import dev.oms.remote.notify.Notifications
import dev.oms.remote.protocol.api.Phase
import dev.oms.remote.protocol.api.RemoteSession
import dev.oms.remote.protocol.api.SessionSignal
import dev.oms.remote.protocol.api.SessionUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service (type `specialUse`) that runs while [RemoteHub.session] is live. It keeps the
 * process (and so the WebSocket) alive in the background, shows an ongoing status notification,
 * and turns [SessionSignal]s into user notifications. It stops when the session ends, fails or is
 * closed; it is never restarted by the system (no auto-reconnect in v1).
 */
class CollabService : LifecycleService() {
    private var lastStartId = 0
    private var inForeground = false
    private var shownOngoing: Pair<String, String>? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            RemoteHub.session.collectLatest { session ->
                if (session == null) {
                    Notifications.cancelAllRequests(this@CollabService)
                    stop()
                } else {
                    observe(session)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        // Every startForegroundService() must be answered with startForeground(), even if we stop at once.
        val session = RemoteHub.session.value
        val state = session?.state?.value
        enterForeground(ongoingNotification(state))
        if (session == null || state?.connection?.phase.isTerminal()) stop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        inForeground = false
        super.onDestroy()
    }

    private suspend fun observe(session: RemoteSession) {
        // Whichever reports the end first (the Ended/Failed signal or the terminal state) wins.
        val end = CompletableDeferred<String>()
        val reason = coroutineScope {
            val states = launch {
                session.state.collect { state ->
                    when (state.connection.phase) {
                        Phase.Ended -> end.complete(state.connection.message ?: getString(R.string.session_ended_default))
                        Phase.Failed -> end.complete(state.connection.message ?: getString(R.string.session_failed_default))
                        else -> updateOngoing(state)
                    }
                }
            }
            val signals = launch {
                session.signals.collect { signal -> onSignal(session, signal, end) }
            }
            // A request that arrived while the app was open gets no notification; if the user then
            // leaves without answering, notify for everything still pending.
            val background = launch {
                var wasForeground = Notifications.appInForeground
                ProcessLifecycleOwner.get().lifecycle.currentStateFlow.collect { lifecycleState ->
                    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
                    if (wasForeground && !foreground) notifyPending(session)
                    wasForeground = foreground
                }
            }
            end.await().also {
                states.cancel()
                signals.cancel()
                background.cancel()
            }
        }
        finish(session, reason)
    }

    private fun onSignal(session: RemoteSession, signal: SessionSignal, end: CompletableDeferred<String>) {
        when (signal) {
            is SessionSignal.NeedsInput -> if (!Notifications.appInForeground) {
                Notifications.postNeedsInput(
                    context = this,
                    roomId = session.link.roomId,
                    sessionTitle = Notifications.sessionTitle(this, session.state.value),
                    request = signal.request,
                )
            }
            is SessionSignal.RequestResolved -> Notifications.cancelRequest(this, session.link.roomId, signal.reqId)
            is SessionSignal.TurnFinished -> if (!Notifications.appInForeground) {
                val title = signal.title?.takeIf { it.isNotBlank() }
                    ?: Notifications.sessionTitle(this, session.state.value)
                Notifications.postTurnFinished(this, title)
            }
            is SessionSignal.Ended -> end.complete(signal.reason.ifBlank { getString(R.string.session_ended_default) })
            is SessionSignal.Failed -> end.complete(signal.message.ifBlank { getString(R.string.session_failed_default) })
        }
    }

    private fun notifyPending(session: RemoteSession) {
        val state = session.state.value
        if (state.connection.phase.isTerminal()) return
        val title = Notifications.sessionTitle(this, state)
        state.pending.forEach { request ->
            Notifications.postNeedsInput(context = this, roomId = session.link.roomId, sessionTitle = title, request = request)
        }
    }

    /** The session ended on its own (host `bye`, fatal error): tell the user, then stop. */
    private fun finish(session: RemoteSession, reason: String) {
        // Replaced or disconnected by the user: no "ended" notice; the collector handles the rest.
        if (RemoteHub.session.value !== session) return
        Notifications.cancelAllRequests(this)
        Notifications.postSessionEnded(
            context = this,
            reason = reason,
            sessionTitle = Notifications.sessionTitle(this, session.state.value),
            silent = Notifications.appInForeground,
        )
        stop()
    }

    private fun updateOngoing(state: SessionUiState) {
        if (!inForeground) return
        val content = Notifications.sessionTitle(this, state) to Notifications.sessionStatus(this, state)
        if (content == shownOngoing) return
        shownOngoing = content
        Notifications.post(this, null, Notifications.ID_ONGOING, Notifications.ongoing(this, content.first, content.second))
    }

    private fun ongoingNotification(state: SessionUiState?): Notification {
        val content = Notifications.sessionTitle(this, state) to Notifications.sessionStatus(this, state)
        shownOngoing = content
        return Notifications.ongoing(this, content.first, content.second)
    }

    private fun enterForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Notifications.ID_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifications.ID_ONGOING, notification)
            }
            inForeground = true
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException (12+) or a type/permission mismatch.
            Log.w(TAG, "Couldn't enter the foreground (${e.javaClass.simpleName})")
            stopSelfResult(lastStartId)
        }
    }

    /** Leaves the foreground and stops, unless a newer start request is already queued. */
    private fun stop() {
        inForeground = false
        shownOngoing = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    private fun Phase?.isTerminal() = this == Phase.Ended || this == Phase.Failed

    companion object {
        private const val TAG = "CollabService"

        /** Starts (or refreshes) the service. Must be called while the app is in the foreground. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CollabService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: the app is in the background.
                Log.w(TAG, "Couldn't start the session service (${e.javaClass.simpleName})")
            }
        }
    }
}
