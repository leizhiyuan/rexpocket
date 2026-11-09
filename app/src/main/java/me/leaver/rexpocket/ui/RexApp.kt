package me.leaver.rexpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel

// The app root: picks the screen for the route and hosts the dialogs that float over all of them.

@Composable
fun RexPocketApp(model: RexViewModel = viewModel()) {
    val route by model.route.collectAsState()
    val hosts by model.hosts.collectAsState()
    val pocket by model.pocket.collectAsState()
    val font by model.fontScale.collectAsState()
    val tailscale by model.tailscale.collectAsState()
    val tailscaleStatus by model.tailscaleStatus.collectAsState()
    val tailnetError by model.tailnetError.collectAsState()
    val opening by model.opening.collectAsState()
    val tailnetPath by model.tailnetPath.collectAsState()
    val alerts by model.alerts.collectAsState()
    val upload by model.upload.collectAsState()
    val context = LocalContext.current
    var tailscaleOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.restore() }
    LifecycleStartEffect(model) {
        model.setForeground(true)
        onStopOrDispose { model.setForeground(false) }
    }
    // A node that still needs a login can only say so through the link it prints, so take the
    // user straight to it instead of leaving a URL to copy.
    val authUrl = tailscaleStatus.authUrl
    LaunchedEffect(authUrl) {
        if (authUrl.isBlank()) return@LaunchedEffect
        openLink(context, authUrl)
    }
    RexTheme {
        Box(Modifier.fillMaxSize().background(Colors.bg)) {
            when (route) {
                Route.Home -> HomeScreen(
                    hosts = hosts,
                    connected = model.host,
                    state = pocket,
                    tailscale = tailscale,
                    tailscaleStatus = tailscaleStatus,
                    tailnetError = tailnetError,
                    tailnetPath = tailnetPath,
                    opening = opening,
                    cachedSessions = model::cachedSessions,
                    onConnect = { model.connect(it) },
                    onOpenSession = model::openSessionOn,
                    onOpenTab = model::openTabOn,
                    onNewSession = model::createSessionOn,
                    onNewTab = model::newTabOn,
                    onDisconnect = model::disconnect,
                    onRetry = model::retryNow,
                    onProbePath = model::probeTailnetPath,
                    onSave = model::saveHost,
                    onDelete = model::deleteHost,
                    onTailscale = { tailscaleOpen = true },
                    alerts = alerts,
                    onAlerts = model::setAlerts,
                )
                Route.Term -> TermScreen(
                    state = pocket,
                    fontScale = font,
                    onFont = model::setFont,
                    onBack = model::showHome,
                    onOpenSession = model::openSession,
                    watchPane = model::watchPane,
                    onRetry = model::retryNow,
                    onDismissNotice = model::dismissNotice,
                    onAct = model::act,
                    upload = upload,
                    onAttach = model::attachUpload,
                    onCancelUpload = model::cancelUpload,
                    onDismissUpload = model::dismissUpload,
                    onRetryUpload = model::retryUpload,
                )
            }
        }
        if (tailscaleOpen) {
            TailscaleDialog(
                initial = tailscale,
                status = tailscaleStatus,
                error = tailnetError,
                onDismiss = { tailscaleOpen = false },
                onJoin = { name ->
                    model.joinTailnet(name)
                    tailscaleOpen = false
                },
                onLeave = {
                    model.leaveTailnet()
                    tailscaleOpen = false
                },
            )
        }
    }
}
