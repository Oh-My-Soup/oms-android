package dev.oms.remote.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.oms.remote.MainActivity
import dev.oms.remote.R
import dev.oms.remote.protocol.api.Phase
import dev.oms.remote.protocol.api.PendingRequest
import dev.oms.remote.protocol.api.SessionUiState

/** Channels, notification builders and posting helpers. Nothing here logs link or request contents. */
object Notifications {
    const val CHANNEL_SESSION = "session"
    const val CHANNEL_NEEDS_INPUT = "needs_input"
    const val CHANNEL_TURN_DONE = "turn_done"
    const val CHANNEL_SESSION_ENDED = "session_ended"

    /** The foreground-service notification. */
    const val ID_ONGOING = 1
    const val ID_TURN_DONE = 2
    const val ID_SESSION_ENDED = 3

    /** Needs-input notifications use this id plus a per-request tag (see [requestTag]). */
    private const val ID_REQUEST = 100
    private const val REQUEST_TAG_PREFIX = "req:"

    /** Request titles can be long (whole file contents); keep notification payloads small. */
    private const val MAX_BODY_CHARS = 1500

    private const val TAG = "Notifications"

    fun createChannels(context: Context) {
        val channels = listOf(
            NotificationChannelCompat.Builder(CHANNEL_NEEDS_INPUT, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.channel_needs_input))
                .setDescription(context.getString(R.string.channel_needs_input_desc))
                .build(),
            NotificationChannelCompat.Builder(CHANNEL_TURN_DONE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.channel_turn_done))
                .setDescription(context.getString(R.string.channel_turn_done_desc))
                .build(),
            NotificationChannelCompat.Builder(CHANNEL_SESSION_ENDED, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.channel_session_ended))
                .setDescription(context.getString(R.string.channel_session_ended_desc))
                .build(),
            NotificationChannelCompat.Builder(CHANNEL_SESSION, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_session))
                .setDescription(context.getString(R.string.channel_session_desc))
                .setShowBadge(false)
                .build(),
        )
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(channels)
    }

    /** True when the user can't currently see needs-input notifications (app or channel blocked). */
    fun blocked(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return true
        val channel = manager.getNotificationChannelCompat(CHANNEL_NEEDS_INPUT) ?: return false
        return channel.importance == NotificationManagerCompat.IMPORTANCE_NONE
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** True while any activity of the app is visible. */
    val appInForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    // ---- Builders -------------------------------------------------------------------------------

    /** Title shown for a session: the agent session title, else the relay/room label. */
    fun sessionTitle(context: Context, state: SessionUiState?): String =
        state?.header?.title?.takeIf { it.isNotBlank() }
            ?: state?.hostLabel?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.session_default_title)

    fun sessionStatus(context: Context, state: SessionUiState?): String {
        if (state == null) return context.getString(R.string.status_connecting)
        val base = when (state.connection.phase) {
            Phase.Connecting -> return context.getString(R.string.status_connecting)
            Phase.Reconnecting -> return context.getString(R.string.status_reconnecting)
            Phase.Ended, Phase.Failed -> return context.getString(R.string.status_ended)
            Phase.Live -> when {
                state.pending.isNotEmpty() -> context.getString(R.string.status_needs_input)
                state.header?.isStreaming == true -> context.getString(R.string.status_working)
                else -> context.getString(R.string.status_idle)
            }
        }
        return if (state.readOnly) {
            context.getString(R.string.status_joined, base, context.getString(R.string.status_read_only))
        } else {
            base
        }
    }

    fun ongoing(context: Context, title: String, status: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_oms)
            .setContentTitle(title)
            .setContentText(status)
            .setContentIntent(openSessionIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                R.drawable.ic_stat_close,
                context.getString(R.string.action_disconnect),
                NotificationActionReceiver.disconnectIntent(context),
            )
            .build()

    fun postNeedsInput(context: Context, roomId: String, sessionTitle: String, request: PendingRequest) {
        val lines = request.title.lines()
        val title: String
        val body: String
        if (request.isApproval) {
            title = context.getString(R.string.notif_approve_title, request.approvalTool.orEmpty().ifBlank { "tool" })
            body = lines.drop(1).joinToString("\n").trim()
        } else {
            title = context.getString(R.string.notif_asking_title, lines.firstOrNull().orEmpty().trim())
            body = lines.drop(1).joinToString("\n").trim()
                .ifEmpty { request.options.joinToString(" · ") { it.label } }
        }
        val shown = body.ifEmpty { sessionTitle }.take(MAX_BODY_CHARS)

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_NEEDS_INPUT)
            .setSmallIcon(R.drawable.ic_stat_oms)
            .setContentTitle(context.getString(R.string.notif_needs_input_public))
            .setContentText(sessionTitle)
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_NEEDS_INPUT)
            .setSmallIcon(R.drawable.ic_stat_oms)
            .setContentTitle(title)
            .setContentText(shown.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(shown))
            .setSubText(sessionTitle)
            .setContentIntent(openSessionIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Commands and file contents stay off the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)

        if (request.isApproval) {
            request.approveOnceLabel?.let { label ->
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_stat_check,
                        label,
                        NotificationActionReceiver.respondIntent(context, roomId, request.reqId, label, approve = true),
                    )
                        // Approving runs a command on the user's computer: require an unlocked device.
                        .setAuthenticationRequired(true)
                        .build(),
                )
            }
            request.denyLabel?.let { label ->
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_stat_close,
                        label,
                        NotificationActionReceiver.respondIntent(context, roomId, request.reqId, label, approve = false),
                    ).build(),
                )
            }
        }
        post(context, requestTag(roomId, request.reqId), ID_REQUEST, builder.build())
    }

    fun postTurnFinished(context: Context, title: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_TURN_DONE)
            .setSmallIcon(R.drawable.ic_stat_oms)
            .setContentTitle(context.getString(R.string.notif_turn_finished, title))
            .setContentIntent(openSessionIntent(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(context, null, ID_TURN_DONE, notification)
    }

    fun postSessionEnded(context: Context, reason: String, sessionTitle: String, silent: Boolean) {
        val notification = NotificationCompat.Builder(context, CHANNEL_SESSION_ENDED)
            .setSmallIcon(R.drawable.ic_stat_oms)
            .setContentTitle(context.getString(R.string.notif_session_ended, reason))
            .setContentText(sessionTitle)
            .setContentIntent(openSessionIntent(context))
            .setAutoCancel(true)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(context, null, ID_SESSION_ENDED, notification)
    }

    // ---- Cancelling -----------------------------------------------------------------------------

    fun cancelRequest(context: Context, roomId: String, reqId: Long) =
        NotificationManagerCompat.from(context).cancel(requestTag(roomId, reqId), ID_REQUEST)

    /** Cancels every needs-input notification (their requests die with the session). */
    fun cancelAllRequests(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications
            .filter { it.id == ID_REQUEST && it.tag?.startsWith(REQUEST_TAG_PREFIX) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    // ---- Internals ------------------------------------------------------------------------------

    @SuppressLint("MissingPermission") // Checked by hasPermission(); SecurityException handled below.
    fun post(context: Context, tag: String?, id: Int, notification: Notification) {
        if (!hasPermission(context)) return
        try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Posting a notification was refused", e)
        }
    }

    private fun requestTag(roomId: String, reqId: Long) = "$REQUEST_TAG_PREFIX$roomId:$reqId"

    /** Opens (or brings back) MainActivity on the session screen. */
    fun openSessionIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SESSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
