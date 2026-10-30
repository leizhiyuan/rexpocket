package app.rexpocket.ui

import android.content.Intent
import android.net.Uri
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
    Box(Modifier.fillMaxSize().background(Colors.bg)) {
        when (route) {
            Route.Home -> HomeScreen(
                hosts = hosts,
                tailscale = tailscale,
                tailscaleStatus = tailscaleStatus,
                onSave = model::saveHost,
                onDelete = model::deleteHost,
                onOpen = model::connect,
                onTailscale = { tailscaleOpen = true },
            )
            Route.Picker -> PickerScreen(
                hostLabel = model.host?.label ?: "Rex",
                state = pocket,
                tailscaleStatus = tailscaleStatus,
                tailscaleEnabled = tailscale.enabled,
                onTailscale = { tailscaleOpen = true },
                onBack = model::disconnect,
                onRetry = model::retryNow,
                onOpen = model::openSession,
                opening = opening,
                onCreate = model::createSession,
            )
            Route.Term -> TermScreen(
                state = pocket,
                composerDrafts = model.drafts,
                fontScale = font,
                onFont = model::setFont,
                onBack = model::showPicker,
                onOpenSession = model::openSession,
                watchPane = model::watchPane,
                onRetry = model::retryNow,
                onDismissNotice = model::dismissNotice,
                onAct = model::act,
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
