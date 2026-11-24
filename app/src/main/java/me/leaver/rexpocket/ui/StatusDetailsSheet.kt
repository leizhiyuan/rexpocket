package me.leaver.rexpocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.TabDetails

/** Test tag of the Status details sheet's text of pane [index] (from 0). */
internal fun statusDetailsPaneTag(index: Int) = "status-details-pane-$index"

/**
 * A tab's Status details: what the phone made of it - the state that colours its dot, the lists of
 * the home it is in - and, pane by pane, every record Rex reported with the phone's summary and
 * which reports the phone and a desk saw. Copy puts the whole as plain text on the clipboard: the
 * person asked for it, so it has the programs' titles and messages, which the diagnostics report
 * never does. The record fields are as Rex names them, in English, as a log is.
 */
@Composable
internal fun StatusDetailsSheet(title: String, details: TabDetails, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.status_details)) {
        Text(
            title,
            color = Colors.muted,
            fontSize = TextStyles.label,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Group {
            SheetItem(stringResource(R.string.status_details_copy), icon = R.drawable.ic_copy, keepOpen = true) {
                clipboardSet(context, details.text(title, now))
                toast(context, context.getString(R.string.status_details_copied))
            }
        }
        Group(stringResource(R.string.status_details_tab), icons = false) {
            DetailText(listOf("phone shows: ${details.phoneShows()}", "home lists: ${details.lists()}"))
        }
        for (pane in details.panes) {
            Group(stringResource(R.string.status_details_pane, pane.index + 1, pane.block.label), icons = false) {
                DetailText(details.paneLines(pane, now), Modifier.testTag(statusDetailsPaneTag(pane.index)))
            }
        }
    }
}

/** Lines of the details, selectable, in the terminal's kind of type, as a log is read. */
@Composable
private fun DetailText(lines: List<String>, modifier: Modifier = Modifier) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        SelectionContainer {
            Text(
                lines.joinToString("\n"),
                color = Colors.ink,
                fontSize = TextStyles.label,
                lineHeight = labelLeading,
                fontFamily = FontFamily.Monospace,
                modifier = modifier,
            )
        }
    }
}
