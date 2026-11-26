package dev.oms.remote

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.oms.remote.MainViewModel.Screen
import dev.oms.remote.notify.Notifications
import dev.oms.remote.pairing.QrScanner
import dev.oms.remote.protocol.api.ConnectionStatus
import dev.oms.remote.protocol.api.Phase
import dev.oms.remote.protocol.api.RemoteSession
import dev.oms.remote.protocol.api.SessionUiState
import dev.oms.remote.ui.HomeScreen
import dev.oms.remote.ui.NoopActions
import dev.oms.remote.ui.OmsTheme
import dev.oms.remote.ui.SessionScreen
import dev.oms.remote.ui.demoUiState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** Re-read on every resume: the user may change it in system settings. */
    private var notificationsBlocked by mutableStateOf(false)

    // Fragment 1.0.0 only arrives transitively via Play services; this is a ComponentActivity, not a
    // FragmentActivity, so the old FragmentActivity permission-result bug the check guards against can't occur.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshNotificationState() }

    private val uiPrefs by lazy { getSharedPreferences(UI_PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        refreshNotificationState()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        MainViewModel.Event.Connected -> askNotificationsOnFirstConnect()
                    }
                }
            }
        }

        setContent {
            OmsTheme {
                val screen by viewModel.screen.collectAsStateWithLifecycle()
                val session by viewModel.session.collectAsStateWithLifecycle()
                BackHandler(enabled = screen != Screen.Home) { viewModel.goHome() }
                val current = session
                when {
                    screen == Screen.Session && current != null -> key(current) { SessionRoute(current) }
                    screen == Screen.Demo -> DemoRoute()
                    else -> HomeRoute()
                }
            }
        }

        // A recreated activity already handled its intent; a restored process must not reconnect.
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        RemoteHub.ensureService()
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationState()
    }

    @Composable
    private fun HomeRoute() {
        val hosts by viewModel.hosts.collectAsStateWithLifecycle()
        val activeHostId by viewModel.activeHostId.collectAsStateWithLifecycle()
        val linkError by viewModel.linkError.collectAsStateWithLifecycle()
        HomeScreen(
            hosts = hosts,
            activeHostId = activeHostId,
            linkError = linkError,
            notificationsBlocked = notificationsBlocked,
            onOpenHost = viewModel::openHost,
            onScanQr = ::startScan,
            onPasteLink = viewModel::pasteLink,
            onRenameHost = { host, name -> viewModel.rename(host.id, name) },
            onDeleteHost = { host -> viewModel.delete(host.id) },
            onRequestNotifications = ::requestNotifications,
            onOpenDemo = viewModel::openDemo,
        )
    }

    @Composable
    private fun SessionRoute(session: RemoteSession) {
        val state by session.state.collectAsStateWithLifecycle()
        SessionScreen(
            state = state,
            actions = session,
            onBack = viewModel::goHome,
            onDisconnect = viewModel::disconnect,
            onRescan = ::startScan,
        )
    }

    @Composable
    private fun DemoRoute() {
        // Built only when the demo is opened; tolerate a not-yet-implemented demo state.
        val state = remember {
            runCatching { demoUiState() }.getOrElse {
                Log.w(TAG, "Demo state unavailable (${it.javaClass.simpleName})")
                SessionUiState(connection = ConnectionStatus(Phase.Live), hostLabel = "demo")
            }
        }
        SessionScreen(
            state = state,
            actions = NoopActions,
            onBack = viewModel::goHome,
            onDisconnect = viewModel::goHome,
            onRescan = ::startScan,
        )
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // Relaunching from Recents replays the original intent: don't re-add or reconnect.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        when (intent.action) {
            ACTION_OPEN_SESSION -> viewModel.openSession()
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (!text.isNullOrBlank()) viewModel.onSharedText(text)
            }
        }
    }

    private fun startScan() {
        // The callback outlives this activity instance (the scanner UI stops it); only touch the ViewModel.
        val model = viewModel
        QrScanner.scan(this) { outcome ->
            when (outcome) {
                is QrScanner.Outcome.Scanned -> model.onScanned(outcome.text)
                is QrScanner.Outcome.Error -> model.showLinkError(outcome.message)
                QrScanner.Outcome.Cancelled -> Unit
            }
        }
    }

    // ---- Notification permission ----------------------------------------------------------------

    private fun refreshNotificationState() {
        notificationsBlocked = Notifications.blocked(this)
    }

    /** Asks once, on the first successful connect (Android 13+). Later requests come from Home. */
    private fun askNotificationsOnFirstConnect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Notifications.hasPermission(this)) return
        if (uiPrefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        launchNotificationPermission()
    }

    /** Home's "enable notifications": the system prompt while Android still shows it, else Settings. */
    private fun requestNotifications() {
        val canPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Notifications.hasPermission(this) &&
            (
                !uiPrefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false) ||
                    shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                )
        if (canPrompt) launchNotificationPermission() else openNotificationSettings()
    }

    private fun launchNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        uiPrefs.edit { putBoolean(KEY_ASKED_NOTIFICATIONS, true) }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        val settings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(settings)
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
    }

    companion object {
        /** Notification content intents: open the current session. */
        const val ACTION_OPEN_SESSION = "dev.oms.remote.action.OPEN_SESSION"

        private const val TAG = "MainActivity"
        private const val UI_PREFS = "oms_ui"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
