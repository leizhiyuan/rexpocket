package me.leaver.rexpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.LoginLinkGate
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.Trouble
import me.leaver.rexpocket.data.HomeMarks
import me.leaver.rexpocket.data.SavedHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import me.leaver.rexpocket.update.InstallResult
import me.leaver.rexpocket.update.UpdateState

// The app root: lays the list and the pane out for the window in hand, and hosts the dialogs that
// float over both.

@Composable
fun RexPocketApp(
    model: RexViewModel = viewModel(),
    /** Opens a link in the browser; a test hands its own in, to see which links opened. */
    openUrl: ((String) -> Unit)? = null,
) {
    val route by model.route.collectAsState()
    val hosts by model.hosts.collectAsState()
    val pocket by model.pocket.collectAsState()
    val font by model.fontScale.collectAsState()
    val terminalTypeface by model.terminalTypeface.collectAsState()
    val terminalFont by model.terminalFont.collectAsState()
    var fontSettingsOpen by remember { mutableStateOf(false) }
    val tailnet by model.tailnet.collectAsState()
    val tailnetStatus by model.tailnetStatus.collectAsState()
    val tailnetError by model.tailnetError.collectAsState()
    val opening by model.opening.collectAsState()
    val tailnetPath by model.tailnetPath.collectAsState()
    val tailnetStage by model.tailnetStage.collectAsState()
    val tailnetView by model.tailnetView.collectAsState()
    val homeRequest by model.homeRequest.collectAsState()
    val connectProgress by model.connectProgress.collectAsState()
    val lives by model.lives.collectAsState()
    val sshKeyQuestion by model.sshKeyQuestion.collectAsState()
    val alerts by model.alerts.collectAsState()
    val alertPrefs by model.alertPrefs.collectAsState()
    val background by model.background.collectAsState()
    val keyArrangement by model.keyArrangement.collectAsState()
    val quickReplies by model.quickReplies.collectAsState()
    val keyBarCtrlC by model.keyBarCtrlC.collectAsState()
    val gestures by model.gestures.collectAsState()
    val keyBar by model.keyBar.collectAsState()
    val upload by model.upload.collectAsState()
    val update by model.update.collectAsState()
    val updateChannel by model.updateChannel.collectAsState()
    val folds by model.folds.collectAsState()
    val homeFilter by model.homeFilter.collectAsState()
    val activityPrefs by model.activityPrefs.collectAsState()
    val seenMarks by model.seenMarks.collectAsState()
    val reopenLastTab by model.reopenLastTab.collectAsState()
    val appearance by model.appearance.collectAsState()
    val share by model.share.collectAsState()
    val hostImport by model.hostImport.step.collectAsState()
    val backup by model.settingsBackup.step.collectAsState()
    val context = LocalContext.current
    // The home screen leaves the composition while a terminal fills the window; its saved state
    // (the open sheet, search, eye, scroll) is kept here for when it comes back.
    val saveable = rememberSaveableStateHolder()
    var tailnetOpen by remember { mutableStateOf(false) }
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
    val open: (String) -> Unit = openUrl ?: { url -> openLink(context, url) }
    // The login link opens by itself only right after a Join or a Sign in that came before the
    // link did; otherwise the Sign in card on Home shows it and waits for the tap.
    val loginGate = remember { LoginLinkGate() }
    LaunchedEffect(tailnetStage) {
        loginGate.linkToOpen(tailnetStage)?.let(open)
    }
    val signIn: (String) -> Unit = { url ->
        if (url.isNotBlank()) {
            loginGate.cancel()
            open(url)
        } else {
            loginGate.asked(tailnetStage)
        }
    }
    RexTheme(appearance.app, appearance.terminal, appearance.text) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Colors.bg)
                // Any touch or key is the person taking over: a launch resume still on its way
                // gives way to it. Watched on the way down, never consumed.
                .pointerInput(model) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            model.userActed()
                        }
                    }
                }
                .onPreviewKeyEvent {
                    model.userActed()
                    false
                },
        ) {
            val window = windowSize()
            // A tablet or a desk window keeps the list beside the pane: a session or a tab is
            // switched without leaving the terminal it was showing. With nothing open, the pane
            // side says so and waits for a pick.
            val home: @Composable (Boolean) -> Unit = { detail -> saveable.SaveableStateProvider("home") {
                HomeScreen(
                    detail = detail,
                    hosts = hosts,
                    connected = model.host,
                    lives = lives,
                    tailnet = tailnet,
                    tailnetStatus = tailnetStatus,
                    tailnetError = tailnetError,
                    tailnetPath = tailnetPath,
                    tailnetStage = tailnetStage,
                    onSignIn = signIn,
                    onTailnetRetry = model::restartTailnet,
                    onTailnetRestart = model::restartTailnetNode,
                    tailnetView = tailnetView,
                    onTailnetConnect = model::connectTailnet,
                    onTailnetDisconnect = model::disconnectTailnet,
                    onTailnetPolicy = model::setTailnetPolicy,
                    tailnetAccounts = remember(model) {
                        TailnetAccountOps(
                            switch = model::switchTailnetAccount,
                            add = model::addTailnetAccount,
                            rename = model::renameTailnetAccount,
                            remove = model::removeTailnetAccount,
                            hostsNaming = model::hostsOnAccount,
                        )
                    },
                    request = homeRequest,
                    onRequestHandled = { model.homeRequest.value = null },
                    opening = opening,
                    cachedSessions = model::cachedSessions,
                    folds = folds,
                    onHostOpen = model::setHostOpen,
                    onFoldSessions = model::setSessionsFolded,
                    onSetFolds = model::setFolds,
                    filter = homeFilter,
                    onHomeFilter = model::setHomeFilter,
                    activityPrefs = activityPrefs,
                    onActivityPrefs = model::setActivityPrefs,
                    seenMarks = seenMarks,
                    onMarkSeen = { hostId, blocks -> model.markSeen(hostId, blocks) },
                    onRestoreSeen = model::restoreSeen,
                    onReply = model::reply,
                    onInterrupt = model::interrupt,
                    onAnswer = model::answerPrompt,
                    onSendPrompt = model::sendPrompt,
                    quickReplies = quickReplies,
                    onQuickReplies = model::setQuickReplies,
                    drafts = model.drafts,
                    onMoveHost = model::moveHost,
                    onRemoveHost = model::removeHost,
                    onRestoreHost = model::restoreHost,
                    notices = model.homeNotices,
                    onFoldSession = model::setSessionFolded,
                    onConnect = { model.connect(it) },
                    onOpenSession = model::openSessionOn,
                    onOpenTab = model::openTabOn,
                    onOpenPane = model::openPaneOn,
                    onNewSession = model::createSessionOn,
                    onNewTab = model::newTabOn,
                    onManage = { hostId, action -> model.manage(action, hostId) },
                    onDisconnect = { model.disconnect(it) },
                    onRetry = { model.retryNow(it) },
                    onProbePath = { model.probeTailnetPath(it) },
                    onSave = model::saveHost,
                    onDelete = model::deleteHost,
                    onTailnet = { tailnetOpen = true },
                    alerts = alerts,
                    onAlerts = model::setAlerts,
                    alertPrefs = alertPrefs,
                    onAlertPrefs = model::setAlertPrefs,
                    background = background,
                    onHostBackground = model::setHostBackground,
                    keyArrangement = keyArrangement,
                    onKeyArrangement = model::setKeyArrangement,
                    keyBarCtrlC = keyBarCtrlC,
                    onKeyBarCtrlC = model::setKeyBarCtrlC,
                    gestures = gestures,
                    onGestures = model::setGestures,
                    keyBar = keyBar,
                    onKeyBar = model::setKeyBar,
                    onFontSettings = { fontSettingsOpen = true },
                    onDiagnosticsReport = { limit -> model.diagnosticReport(limit) },
                    update = update,
                    onUpdate = { updateTapped(update) },
                    updateChannel = updateChannel,
                    onUpdateChannel = model::setUpdateChannel,
                    reopenLastTab = reopenLastTab,
                    onReopenLastTab = model::setReopenLastTab,
                    appearance = appearance,
                    onAppearance = model::setAppearance,
                    onImportHosts = model.hostImport::open,
                    onBackup = model.settingsBackup::open,
                    hostTester = model.hostTester,
                )
            } }
            val term: @Composable (Boolean) -> Unit = { embedded ->
                TermScreen(
                    embedded = embedded,
                    keyBarWithKeyboard = keyBar,
                    keyArrangement = keyArrangement,
                    keyBarCtrlC = keyBarCtrlC,
                    gestures = gestures,
                    state = pocket,
                    fontScale = font,
                    terminalTypeface = terminalTypeface,
                    onFont = model::setFont,
                    onBack = model::showHome,
                    onOpenSession = model::openSession,
                    onOpenTab = model::openTab,
                    watchPane = model::watchPane,
                    paneCopies = model::paneCopies,
                    onRetry = { model.retryNow() },
                    onDismissNotice = model::dismissNotice,
                    onAct = model::act,
                    upload = upload,
                    onAttach = model::attachUpload,
                    onCancelUpload = model::cancelUpload,
                    onDismissUpload = model::dismissUpload,
                    onRetryUpload = model::retryUpload,
                    quickReplies = quickReplies.items,
                    drafts = model.drafts,
                    draftHost = model.host?.id,
                    seenMark = { block -> model.host?.id?.let { seenMarks[HomeMarks.key(it, block)] } },
                )
            }
            when (layoutFor(window, hasHosts = hosts.isNotEmpty(), terminalOpen = route == Route.Term)) {
                WindowLayout.ListDetail -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val list = listPaneWidth(maxWidth)
                    val open = route == Route.Term
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(list).fillMaxHeight()) { home(open) }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Colors.line))
                        Box(Modifier.weight(1f).fillMaxHeight()) { if (open) term(true) else NoPaneOpen() }
                    }
                }
                WindowLayout.CappedColumn -> BoxWithConstraints(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.width(minOf(maxWidth, COLUMN_MAX)).fillMaxHeight()) { home(false) }
                }
                WindowLayout.OneColumn -> if (route == Route.Term) term(false) else home(false)
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
                    model.userActed()
                    offeredOnce = offeredOnce + offerKey
                    updateTapped(offered)
                },
                onLater = {
                    model.userActed()
                    offeredOnce = offeredOnce + offerKey
                },
            )
        }
        share?.let { offer ->
            ShareSheet(
                offer = offer,
                hosts = hosts,
                connected = model.host,
                state = pocket,
                progress = connectProgress,
                stage = tailnetStage,
                network = tailnetName(tailnet.backend),
                onSend = model::sendShare,
                onChoose = model::chooseShareTarget,
                onPick = model::shareTo,
                onConnect = model::shareConnect,
                onCancel = model::cancelShare,
            )
        }
        backup?.let { step -> SettingsBackupSheet(step = step, backupper = model.settingsBackup) }
        hostImport?.let { step ->
            HostImportSheet(
                step = step,
                connected = hosts.filter { lives[it.id]?.state?.phase == Phase.Live },
                importer = model.hostImport,
            )
        }
        if (fontSettingsOpen) {
            FontSettingsDialog(terminalFont, terminalTypeface, model::selectTerminalFont) { fontSettingsOpen = false }
        }
        if (tailnetOpen) {
            TailnetDialog(
                initial = tailnet,
                backends = model.tailnetBackends,
                status = tailnetStatus,
                error = tailnetError,
                onDismiss = { tailnetOpen = false },
                onJoin = { backend, name, managementUrl, setupKey ->
                    loginGate.asked(tailnetStage)
                    model.joinTailnet(backend, name, managementUrl, setupKey)
                    tailnetOpen = false
                },
                onLeave = {
                    loginGate.cancel()
                    model.leaveTailnet()
                    tailnetOpen = false
                },
            )
        }
        // Any connected SSH host can ask, not only the one on screen: it is waiting for the answer.
        sshKeyQuestion?.let { asking ->
            val sshProblem = asking.state.problem ?: return@let
            SshHostKeyDialog(
                host = asking.target,
                changed = sshProblem.trouble == Trouble.SshHostKeyChanged,
                fingerprint = sshProblem.detail.orEmpty(),
                onTrust = { fingerprint -> model.trustSshHostKey(asking.target.id, fingerprint) },
                onCancel = { model.disconnect(asking.target.id) },
            )
        }
    }
}

/**
 * The pane side of a window that has room for two, with nothing open in it yet: the list on the
 * left is where a pane is picked, and it stays there.
 */
@Composable
private fun NoPaneOpen() {
    Column(
        Modifier.fillMaxSize().background(Colors.terminal).statusBarsPadding().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Glyph(R.drawable.ic_sessions, Colors.muted, size = 44.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.pane_none_title),
            color = Colors.ink,
            fontSize = TextStyles.title,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.pane_none_body),
            color = Colors.muted,
            fontSize = TextStyles.label,
            lineHeight = labelLeading,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}

/**
 * The SSH key of the machine, before anything about the account is sent. First use and a changed
 * key take the same dialog; only the wording, and how loudly it warns, differ.
 */
@Composable
private fun SshHostKeyDialog(
    host: SavedHost,
    changed: Boolean,
    fingerprint: String,
    onTrust: (String) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Colors.surface,
        title = { Text(stringResource(R.string.ssh_host_key_title, host.label), color = Colors.ink) },
        text = {
            Text(
                stringResource(
                    if (changed) R.string.ssh_host_key_changed_body else R.string.ssh_host_key_unknown_body,
                    fingerprint,
                ),
                color = Colors.muted,
            )
        },
        confirmButton = {
            TextButton(onClick = { onTrust(fingerprint) }) {
                Text(stringResource(R.string.action_trust), color = if (changed) Colors.danger else Colors.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
        },
    )
}
