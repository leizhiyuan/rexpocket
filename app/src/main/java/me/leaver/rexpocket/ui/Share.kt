package me.leaver.rexpocket.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ConnectProgress
import me.leaver.rexpocket.core.FileUpload
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.data.SavedHost

/** A file another app shared: its content URI, the name the desktop will see, and its size when the app said. */
data class SharedFile(val uri: Uri, val name: String, val size: Long?)

/** What another app shared: files to upload, or else text to paste. */
data class ShareRequest(val text: String?, val files: List<SharedFile>)

/** The pane a share goes to. */
data class ShareTarget(val hostId: String, val sessionId: String, val blockId: String)

/** A share waiting for its tap, the pane it would go to, and whether the pane picker is open. */
data class ShareOffer(val request: ShareRequest, val target: ShareTarget?, val choosing: Boolean)

/**
 * Reads an `ACTION_SEND` or `ACTION_SEND_MULTIPLE` intent. Files win over text: an image with a
 * caption uploads the image. Only `content:` URIs are taken, and none of this app's own, so a
 * crafted share cannot make the app read its private files (`file:`) and send them to a pane.
 */
internal fun readShare(context: Context, intent: Intent): ShareRequest? {
    val uris = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> return null
    }.filter { it.scheme == ContentResolver.SCHEME_CONTENT && it.authority?.startsWith(context.packageName) != true }.distinct()
    val files = uris.map { SharedFile(it, uploadName(context, it), sharedSize(context, it)) }
    val text = if (files.isEmpty()) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotEmpty() } else null
    if (files.isEmpty() && text == null) return null
    return ShareRequest(text, files)
}

private fun sharedSize(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }
}.getOrNull()

/**
 * The sheet a share opens: what came in, the pane it goes to (host, session, tab), the connection's
 * progress while it comes up, and Send, which is the only way anything reaches the pane. "Choose
 * another" opens the picker, which is also where the sheet starts when no pane is remembered.
 */
@Composable
internal fun ShareSheet(
    offer: ShareOffer,
    hosts: List<SavedHost>,
    connected: SavedHost?,
    state: PocketState,
    progress: ConnectProgress?,
    stage: TailnetStage,
    network: String,
    onSend: () -> Unit,
    onChoose: (Boolean) -> Unit,
    onPick: (ShareTarget) -> Unit,
    onConnect: (SavedHost) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val request = offer.request
    if (offer.choosing) {
        SharePicker(hosts, connected, state, offer.target, onPick, onConnect, onBack = { onChoose(false) }.takeIf { offer.target != null }, onCancel)
        return
    }
    val target = offer.target
    val live = target != null && connected?.id == target.hostId && state.phase == Phase.Live
    val view = target?.let { state.views[it.sessionId] }
    val window = view?.windows?.firstOrNull { w -> w.blocks.any { it.id == target.blockId } }
    // Once live, a pane that is gone is no target: the picker says what is left.
    val gone = live && window == null
    val tooLarge = request.files.any { (it.size ?: 0) > FileUpload.MAX_BYTES }
    val host = hosts.firstOrNull { it.id == target?.hostId }
    val where = listOfNotNull(
        host?.label,
        view?.label ?: state.sessions.firstOrNull { it.id == target?.sessionId }?.label,
        if (view != null && window != null) tabTitle(view, window, state) else null,
    ).joinToString(" · ")
    ActionSheet(onDismiss = onCancel, title = stringResource(R.string.share_title)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val what = when {
                request.files.size == 1 -> request.files.first().let { f ->
                    f.name + (f.size?.let { " · " + Formatter.formatShortFileSize(context, it) } ?: "")
                }
                request.files.isNotEmpty() -> pluralStringResource(R.plurals.share_files, request.files.size, request.files.size)
                else -> request.text.orEmpty()
            }
            Text(what, color = Colors.ink, fontSize = TextStyles.body, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(if (request.files.isEmpty()) R.string.share_pastes else R.string.share_uploads),
                color = Colors.muted,
                fontSize = TextStyles.label,
            )
            if (tooLarge) Text(stringResource(R.string.share_too_large), color = Colors.danger, fontSize = TextStyles.label)
            Text(
                if (gone) stringResource(R.string.share_pane_gone) else stringResource(R.string.share_to, where),
                color = if (gone) Colors.danger else Colors.ink,
                fontSize = TextStyles.body,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (!live && connected?.id == target?.hostId) {
                val problem = state.problem
                when {
                    problem != null -> Text(problemText(problem, state.endpointLabel, network), color = Colors.danger, fontSize = TextStyles.label)
                    progress != null -> ConnectProgressLine(progress, stage, network)
                }
            }
        }
        Group {
            SheetItem(
                stringResource(if (request.files.isEmpty()) R.string.share_paste else R.string.share_send),
                Colors.accent,
                icon = if (request.files.isEmpty()) R.drawable.ic_paste else R.drawable.ic_share,
                keepOpen = true,
                enabled = live && !gone && !tooLarge,
                onClick = onSend,
            )
            SheetItem(stringResource(R.string.share_choose), icon = R.drawable.ic_swap, keepOpen = true) { onChoose(true) }
        }
    }
}

/**
 * Every pane the phone can send to: the open host's sessions, tabs and panes, and the other hosts,
 * which connect when tapped so their panes can be listed.
 */
@Composable
private fun SharePicker(
    hosts: List<SavedHost>,
    connected: SavedHost?,
    state: PocketState,
    current: ShareTarget?,
    onPick: (ShareTarget) -> Unit,
    onConnect: (SavedHost) -> Unit,
    onBack: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    ActionSheet(onDismiss = onCancel, title = stringResource(R.string.share_pick), onBack = onBack) {
        if (hosts.isEmpty()) {
            Text(stringResource(R.string.share_no_hosts), color = Colors.muted, fontSize = TextStyles.body, modifier = Modifier.padding(16.dp))
        }
        hosts.forEach { host ->
            val live = connected?.id == host.id && state.phase == Phase.Live
            if (!live) {
                Group {
                    SheetItem(
                        host.label,
                        icon = R.drawable.ic_host,
                        keepOpen = true,
                        supporting = if (connected?.id == host.id) phaseText(state) else stringResource(R.string.share_connect),
                        enabled = connected?.id != host.id,
                    ) { onConnect(host) }
                }
                return@forEach
            }
            state.sessions.forEach { session ->
                val view = state.views[session.id] ?: return@forEach
                Group(label = "${host.label} · ${view.label}") {
                    if (view.windows.isEmpty()) {
                        SheetItem(stringResource(R.string.no_tabs), enabled = false) {}
                    }
                    view.windows.forEach { window ->
                        val tab = tabTitle(view, window, state)
                        window.blocks.forEachIndexed { index, block ->
                            val label = if (window.blocks.size == 1) tab else "$tab · ${index + 1} ${paneTitle(block, state.blockStatus[block.id], state.programs[block.id])}"
                            SheetItem(
                                label,
                                marked = current?.blockId == block.id,
                                keepOpen = true,
                            ) { onPick(ShareTarget(host.id, session.id, block.id)) }
                        }
                    }
                }
            }
        }
    }
}
