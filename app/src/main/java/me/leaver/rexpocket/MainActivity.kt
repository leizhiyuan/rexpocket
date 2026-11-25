package me.leaver.rexpocket

import android.content.Intent
import android.os.Bundle
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import me.leaver.rexpocket.alerts.AlertLink
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.alerts.HomeWidget
import me.leaver.rexpocket.alerts.WatchService
import me.leaver.rexpocket.ui.Connections
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.leaver.rexpocket.core.HostLink
import me.leaver.rexpocket.ui.LockGate
import me.leaver.rexpocket.ui.AuthResult
import me.leaver.rexpocket.ui.rememberDeviceAuthenticator
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.ui.RexPocketApp
import me.leaver.rexpocket.ui.RexViewModel
import me.leaver.rexpocket.ui.TerminalShortcuts
import me.leaver.rexpocket.ui.terminalShortcutGroup
import me.leaver.rexpocket.ui.hostListToOpen
import me.leaver.rexpocket.ui.readShare

class MainActivity : ComponentActivity() {
    private val model: RexViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The journal that holds the last few hours, a recording the last process left running (a
        // bug that kills the app is the one worth recording), and the crash handler that writes a
        // failure into it before the app goes.
        Diagnostics.install(this)
        // A tapped alert opens its pane; a recreated activity has already acted on its intent.
        if (savedInstanceState == null) alert(intent)
        // Something shared from another app waits on its sheet for a tap; it is never sent on its own.
        if (savedInstanceState == null) received(intent)
        // Block screenshots: the window is secure, so screenshots, screen recording and the recents
        // thumbnail show nothing of it. Followed as the setting changes.
        lifecycleScope.launch {
            model.security.collect { prefs ->
                if (prefs.blockScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent {
            val locked by model.locked.collectAsState()
            LockGate(
                locked = locked,
                authenticate = rememberDeviceAuthenticator(),
                onAsking = model::lockAsking,
                onResult = { result -> if (result == AuthResult.Confirmed) model.unlocked() else model.lockNotConfirmed() },
            ) { RexPocketApp(model) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        alert(intent)
        received(intent)
    }

    /**
     * A tapped notification: its pane, its host's alerts, or Settings › Background and
     * notifications. An alert link the app cannot check (one from before a reinstall) opens the
     * home and says it expired.
     */
    private fun alert(intent: Intent) {
        when (intent.action) {
            Alerts.ACTION_OPEN_NOTIFICATIONS -> return model.openNotifications()
            // The widget's tap: the home on Activity, where the counts come from.
            HomeWidget.ACTION_OPEN_ACTIVITY -> return model.openActivity()
        }
        when (val link = Alerts.linkOf(this, intent)) {
            is AlertLink.Pane -> model.openAlert(link.target)
            is AlertLink.Host -> model.openHostAlerts(link.hostId)
            AlertLink.Expired -> {
                model.alertExpired()
                android.widget.Toast.makeText(this, R.string.alerts_expired, android.widget.Toast.LENGTH_LONG).show()
            }
            null -> Unit
        }
    }

    /**
     * A settings backup opened with the app or shared to it goes to Settings › Backup, a host list to
     * Import hosts; each shows what it would change before anything does. Any other share waits on
     * its sheet for a tap.
     */
    private fun received(intent: Intent) {
        // A launcher shortcut opens its tab, connecting its host first.
        me.leaver.rexpocket.ui.LauncherShortcuts.read(intent)?.let {
            model.openShortcut(it)
            return
        }
        // A tile's long press, a tile whose tailnet hosts use, or a page asked for.
        when (intent.action) {
            android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES -> {
                @Suppress("DEPRECATION")
                val component = intent.getParcelableExtra<android.content.ComponentName>(Intent.EXTRA_COMPONENT_NAME)
                if (component?.className == me.leaver.rexpocket.alerts.BackgroundTile::class.java.name) {
                    model.openNotifications()
                    return
                }
                val slot = me.leaver.rexpocket.alerts.TileStore.slotOf(component) ?: return
                val item = me.leaver.rexpocket.alerts.TileStore(this).of(slot)
                if (item == null) startActivity(me.leaver.rexpocket.alerts.TileStore.pageIntent(this, null, slot)) else model.openPage(item.key)
                return
            }
            me.leaver.rexpocket.alerts.TileStore.ACTION_OPEN_PAGE -> {
                intent.getStringExtra(me.leaver.rexpocket.alerts.TileStore.EXTRA_ITEM)?.let(model::openPage)
                return
            }
            me.leaver.rexpocket.alerts.TileStore.ACTION_CONFIRM_OFF -> {
                model.askTailnetOff()
                return
            }
        }
        // A host to add, from a rexpocket://add link or a QR code the camera read: the dialog
        // opens filled in, and nothing is saved until the person taps Save.
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme.equals(HostLink.SCHEME, ignoreCase = true)) {
            model.openHostLink(intent.dataString.orEmpty())
            return
        }
        // A settings backup names its own format; anything else opened is a host list, as before.
        hostListToOpen(this, intent)?.let { uri ->
            model.settingsBackup.readUri(uri) { model.hostImport.readUri(uri) }
            return
        }
        val share = readShare(this, intent) ?: return
        val toHosts = { if (!model.hostImport.offerShare(share, intent.type, model::offerShare)) model.offerShare(share) }
        if (!model.settingsBackup.offerShare(share, intent.type, toHosts)) toHosts()
    }

    /** Android's keyboard shortcuts helper (Meta+/) lists the terminal screen's keys while it is up. */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        if (TerminalShortcuts.shown > 0) data += terminalShortcutGroup(this)
    }

    // Leaving the app is the usual trigger for a tunnel that has to find its way back, so the
    // moment it happens belongs in the diagnostics next to the node's own log.
    override fun onStart() {
        super.onStart()
        Diagnostics.event(LIFECYCLE_TAG, "foreground")
        // Back after long enough away, with the app lock on: locked until the phone says who it is.
        model.lockBack()
    }

    override fun onStop() {
        Diagnostics.event(LIFECYCLE_TAG, "background")
        // The widget's counts as the app leaves: the background watch keeps them fresh from here,
        // when it runs; without it they say when they were read.
        runCatching { HomeWidget.update(this, Connections.of(application), background = WatchService.running) }
        model.lockAway()
        super.onStop()
    }
}

private const val LIFECYCLE_TAG = "RexPocketLife"
