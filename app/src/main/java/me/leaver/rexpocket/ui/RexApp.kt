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
import me.leaver.rexpocket.R
import me.leaver.rexpocket.update.InstallResult
import me.leaver.rexpocket.update.UpdateState

// The app root: picks the screen for the route and hosts the dialogs that float over all of them.

@Composable
fun RexPocketApp(model: RexViewModel = viewModel()) {
    val route by model.route.collectAsState()
    val hosts by model.hosts.collectAsState()
    val pocket by model.pocket.collectAsState()
    val font by model.fontScale.collectAsState()
    val terminalTypeface by model.terminalTypeface.collectAsState()
    val terminalFont by model.terminalFont.collectAsState()
    var fontSettingsOpen by remember { mutableStateOf(false) }
    val tailscale by model.tailscale.collectAsState()
    val tailscaleStatus by model.tailscaleStatus.collectAsState()
    val tailnetError by model.tailnetError.collectAsState()
    val opening by model.opening.collectAsState()
    val tailnetPath by model.tailnetPath.collectAsState()
    val alerts by model.alerts.collectAsState()
    val upload by model.upload.collectAsState()
    val update by model.update.collectAsState()
    val context = LocalContext.current
    var tailscaleOpen by remember { mutableStateOf(false) }
    // Each offer is shown once per app run; the settings row still has it afterwards.
    var offeredOnce by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) { model.restore() }
    // Once a day: look for a newer build and say so. Nothing is downloaded until it is asked for.
    LaunchedEffect(Unit) { model.checkForUpdates() }

    // The settings row and the offer do the same next thing: download what was found, install what
    // was downloaded, or ask again. Nothing here runs on its own.
    val install = {
        if (model.installUpdate() == InstallResult.NeedsPermission) {
            toast(context, context.getString(R.string.update_allow_install))
        }
    }
    val updateTapped: (UpdateState) -> Unit = { state ->
        when (state) {
            is UpdateState.Available -> model.downloadUpdate()
            is UpdateState.Ready -> install()
            is UpdateState.Downloading -> Unit
            else -> model.checkForUpdates(force = true)
        }
    }
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
                    onFontSettings = { fontSettingsOpen = true },
                    onDiagnosticsReport = { limit -> model.diagnosticReport(limit) },
                    update = update,
                    onUpdate = { updateTapped(update) },
                )
                Route.Term -> TermScreen(
                    state = pocket,
                    fontScale = font,
                    terminalTypeface = terminalTypeface,
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
        val offered = update
        val offeredTag = when (offered) {
            is UpdateState.Available -> offered.tag
            is UpdateState.Ready -> offered.tag
            else -> null
        }
        val offerKey = offeredTag?.let { if (offered is UpdateState.Ready) "$it-ready" else "$it-available" }
        if (offeredTag != null && offerKey != null && offerKey !in offeredOnce) {
            UpdateOfferDialog(
                tag = offeredTag,
                downloaded = offered is UpdateState.Ready,
                onConfirm = {
                    offeredOnce = offeredOnce + offerKey
                    updateTapped(offered)
                },
                onLater = { offeredOnce = offeredOnce + offerKey },
            )
        }
        if (fontSettingsOpen) {
            FontSettingsDialog(terminalFont, model::selectTerminalFont) { fontSettingsOpen = false }
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
