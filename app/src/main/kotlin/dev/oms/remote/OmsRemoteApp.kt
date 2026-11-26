package dev.oms.remote

import android.app.Application
import android.util.Log
import dev.oms.remote.notify.Notifications
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class OmsRemoteApp : Application() {
    /** Outlives activities and the service; sessions process their frames here. */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Uncaught error in a background job (${e.javaClass.name})", e)
        },
    )

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // v1: no auto-reconnect after process death; the user picks a host on Home.
        RemoteHub.init(this, appScope)
    }

    private companion object {
        const val TAG = "OmsRemoteApp"
    }
}
