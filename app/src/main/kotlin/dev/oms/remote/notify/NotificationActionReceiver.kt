package dev.oms.remote.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.oms.remote.RemoteHub

/** Handles notification buttons: Approve / Deny on a request, Disconnect on the ongoing notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESPOND -> respond(context, intent)
            ACTION_DISCONNECT -> RemoteHub.disconnect()
        }
    }

    private fun respond(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: return
        val reqId = intent.getLongExtra(EXTRA_REQ_ID, -1L)
        val label = intent.getStringExtra(EXTRA_LABEL)
        Notifications.cancelRequest(context, roomId, reqId)
        if (reqId < 0 || label == null) return
        // Only answer the request this notification was posted for: same room, still pending.
        val session = RemoteHub.session.value ?: return
        if (session.link.roomId != roomId) return
        if (session.state.value.pending.none { it.reqId == reqId }) return
        session.respond(reqId, label)
    }

    companion object {
        private const val ACTION_RESPOND = "dev.oms.remote.action.RESPOND"
        private const val ACTION_DISCONNECT = "dev.oms.remote.action.DISCONNECT"
        private const val EXTRA_ROOM_ID = "roomId"
        private const val EXTRA_REQ_ID = "reqId"
        private const val EXTRA_LABEL = "label"

        fun respondIntent(context: Context, roomId: String, reqId: Long, label: String, approve: Boolean): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_RESPOND)
                // Distinct identity per request and button so PendingIntents never share extras.
                .setIdentifier("respond:$roomId:$reqId:${if (approve) "approve" else "deny"}")
                .putExtra(EXTRA_ROOM_ID, roomId)
                .putExtra(EXTRA_REQ_ID, reqId)
                .putExtra(EXTRA_LABEL, label)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun disconnectIntent(context: Context): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_DISCONNECT)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
