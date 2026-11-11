package me.leaver.rexpocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.leaver.rexpocket.BuildConfig
import me.leaver.rexpocket.R
import me.leaver.rexpocket.diagnostics.Diagnostics

/**
 * The report for a bug: a recording a person starts, reproduces the problem in, and stops - instead
 * of describing from memory what happened. The sheet says what goes into it before anything leaves
 * the phone: the addresses and the names their hosts go by are replaced, and neither tab titles nor
 * what a pane printed are ever part of it.
 */
@Composable
internal fun DiagnosticsSheet(
    onDismiss: () -> Unit,
    report: (limit: Int) -> String,
    onRecordingChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var capture by remember { mutableStateOf(Diagnostics.capture(context)) }
    // While a recording runs, the sheet keeps up with it: how long, and how much it has caught.
    LaunchedEffect(capture != null) {
        while (capture != null) {
            delay(1_000)
            capture = Diagnostics.capture(context)
        }
    }
    fun stop(reason: String) {
        Diagnostics.stop(context, reason)
        capture = null
        onRecordingChanged(false)
    }

    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.diagnostics)) {
        val running = capture
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                if (running == null) {
                    stringResource(R.string.diagnostics_what)
                } else {
                    stringResource(R.string.diagnostics_recording_what, elapsed(running), running.lines)
                },
                color = Colors.muted,
                fontSize = TextStyles.label,
                lineHeight = labelLeading,
            )
        }
        if (running != null) {
            Group {
                SheetItem(stringResource(R.string.diagnostics_stop_share), icon = R.drawable.ic_share) {
                    stop("stopped by the person")
                    if (!Diagnostics.share(context, report(Int.MAX_VALUE))) {
                        toast(context, context.getString(R.string.diagnostics_report_failed))
                    }
                }
                SheetItem(stringResource(R.string.diagnostics_stop), icon = R.drawable.ic_close) {
                    stop("stopped by the person")
                    toast(context, context.getString(R.string.diagnostics_stopped))
                }
            }
        } else {
            Group {
                SheetItem(stringResource(R.string.diagnostics_start)) {
                    Diagnostics.start(context)
                    capture = Diagnostics.capture(context)
                    onRecordingChanged(true)
                }
                // No recording needed for a problem that already happened: the journal has the last
                // few hours, and this is the report of them.
                SheetItem(stringResource(R.string.diagnostics_share), icon = R.drawable.ic_share) {
                    if (!Diagnostics.share(context, report(Int.MAX_VALUE))) {
                        toast(context, context.getString(R.string.diagnostics_report_failed))
                    }
                }
                SheetItem(stringResource(R.string.diagnostics_copy), icon = R.drawable.ic_copy) {
                    clipboardSet(context, report(PASTE_CHARS))
                    toast(context, context.getString(R.string.diagnostics_copied))
                }
            }
        }
        // The report goes on the clipboard first, so it is already there when the issue page
        // opens: pasting it is the only thing left to do.
        Group {
            SheetItem(stringResource(R.string.diagnostics_issue), icon = R.drawable.ic_open_in_new) {
                clipboardSet(context, report(PASTE_CHARS))
                toast(context, context.getString(R.string.diagnostics_copied))
                openLink(context, issueUrl())
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** How long the recording has run, as minutes and seconds. */
private fun elapsed(capture: Diagnostics.Capture): String {
    val seconds = ((System.currentTimeMillis() - capture.startedAt) / 1_000).coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** A recording can be megabytes; a paste into an issue box cannot. */
private const val PASTE_CHARS = 60_000

/** Where a bug report goes: the project's issues, with the version it came from already in the title. */
private fun issueUrl(): String =
    "https://github.com/leizhiyuan/rexpocket/issues/new?title=RexPocket%20${BuildConfig.VERSION_NAME}%3A%20"
