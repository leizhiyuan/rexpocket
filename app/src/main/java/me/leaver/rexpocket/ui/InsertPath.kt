package me.leaver.rexpocket.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.DirListing
import me.leaver.rexpocket.core.DirStatus
import me.leaver.rexpocket.core.LIST_DIR_LIMIT
import me.leaver.rexpocket.core.shellPath

// Insert path: the host's folders as the pane's program sees them, over Rex's `list_dir`, and the
// path picked typed into the pane, quoted for a shell. See terminal-controls.md, "Insert path".

internal const val INSERT_PATH_HERE_TAG = "insert-path-here"
internal const val INSERT_PATH_UP_TAG = "insert-path-up"
internal fun insertPathEntryTag(name: String) = "insert-path-entry-$name"

/** What the browser shows: a folder being read, one read, or why it could not be. */
internal sealed interface FolderView {
    data object Loading : FolderView
    data class Shown(val listing: DirListing) : FolderView
    data object Failed : FolderView
}

/**
 * The browser. It starts where the pane's program is ("" is its working directory to Rex), and
 * [list] reads a folder: null when it could not (the pane went, or this Rex has no `list_dir`).
 * A folder opens on a tap; a file, or the folder itself, is typed with [onInsert] as a shell
 * word, the home as `~`.
 */
@Composable
internal fun InsertPathSheet(
    list: suspend (path: String) -> DirListing?,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by rememberSaveable { mutableStateOf("") }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var view by remember { mutableStateOf<FolderView>(FolderView.Loading) }
    LaunchedEffect(path) {
        view = FolderView.Loading
        view = runCatching { list(path) }.getOrNull()?.let { FolderView.Shown(it) } ?: FolderView.Failed
    }
    val listing = (view as? FolderView.Shown)?.listing
    ActionSheet(onDismiss = onDismiss, title = listing?.let { displayPath(it.path, it.home) } ?: stringResource(R.string.insert_path)) {
        when (val v = view) {
            FolderView.Loading -> Note(stringResource(R.string.insert_path_loading))
            FolderView.Failed -> {
                Note(stringResource(R.string.insert_path_failed))
                // Back to the folder it was opened from, which could be read.
                DirListing.parentOf(path)?.takeIf { path.isNotEmpty() }?.let { up ->
                    Group {
                        SheetItem(
                            stringResource(R.string.insert_path_up, up),
                            icon = R.drawable.ic_arrow_up,
                            keepOpen = true,
                            modifier = Modifier.testTag(INSERT_PATH_UP_TAG),
                        ) { path = up }
                    }
                }
            }
            is FolderView.Shown -> {
                val l = v.listing
                Group {
                    if (l.status == DirStatus.Ok) {
                        SheetItem(
                            stringResource(R.string.insert_path_here),
                            Colors.accent,
                            icon = R.drawable.ic_return,
                            modifier = Modifier.testTag(INSERT_PATH_HERE_TAG),
                        ) { onInsert(shellPath(l.path, l.home)) }
                    }
                    l.parent?.let { up ->
                        SheetItem(
                            stringResource(R.string.insert_path_up, displayPath(up, l.home)),
                            icon = R.drawable.ic_arrow_up,
                            keepOpen = true,
                            modifier = Modifier.testTag(INSERT_PATH_UP_TAG),
                        ) { path = up }
                    }
                    if (l.entries.any { it.hidden }) {
                        SheetItem(
                            stringResource(if (hidden) R.string.insert_path_hide_hidden else R.string.insert_path_show_hidden),
                            icon = R.drawable.ic_eye,
                            keepOpen = true,
                        ) { hidden = !hidden }
                    }
                }
                when (l.status) {
                    DirStatus.Ok -> Unit
                    DirStatus.Missing -> Note(stringResource(R.string.insert_path_missing))
                    DirStatus.NotDir -> Note(stringResource(R.string.insert_path_not_dir))
                    DirStatus.Denied -> Note(stringResource(R.string.insert_path_denied))
                    DirStatus.Unknown -> Note(stringResource(R.string.insert_path_failed))
                }
                val shown = l.shown(hidden)
                if (l.status == DirStatus.Ok && shown.isEmpty()) Note(stringResource(R.string.insert_path_empty))
                if (shown.isNotEmpty()) {
                    Group {
                        for (entry in shown) {
                            SheetItem(
                                entry.name,
                                color = if (entry.hidden) Colors.muted else Colors.ink,
                                icon = if (entry.file) R.drawable.ic_file else R.drawable.ic_folder,
                                // A folder opens in place; a file is typed, and the sheet goes.
                                keepOpen = !entry.file,
                                opens = !entry.file,
                                modifier = Modifier.testTag(insertPathEntryTag(entry.name)),
                            ) {
                                if (entry.file) onInsert(shellPath(l.child(entry), l.home)) else path = l.child(entry)
                            }
                        }
                    }
                }
                if (l.truncated) Note(stringResource(R.string.insert_path_truncated, LIST_DIR_LIMIT))
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        color = Colors.muted,
        fontSize = TextStyles.label,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 14.dp),
    )
}

/** A folder as the title shows it: the home as `~`. */
internal fun displayPath(path: String, home: String): String {
    val h = home.trimEnd('/')
    return when {
        h.isEmpty() -> path
        path == h -> "~"
        path.startsWith("$h/") -> "~/" + path.removePrefix("$h/")
        else -> path
    }
}
