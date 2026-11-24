package me.leaver.rexpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.MAX_MATCHES
import me.leaver.rexpocket.core.SearchMatch
import me.leaver.rexpocket.core.nextCurrent
import me.leaver.rexpocket.core.searchScrollback
import me.leaver.rexpocket.terminal.TerminalSession

// Find in the pane's scrollback: a bar over the pane with the query, the count, the way to the next
// and previous match and the case switch. The pane's own terminal is searched - the screen and every
// row of history it holds, which is what Rex's snapshot gave it and all the output since - and the
// matches are drawn on the pane. See docs/design/terminal-controls.md, "Find".

/**
 * What Find has: the query, the case switch, the matches (rows from [firstRow] of the scrollback)
 * and the one it is on. The terminal it searches is given to [search], so a test can give it rows.
 */
@Stable
internal class FindModel {
    var query by mutableStateOf(TextFieldValue(""))
    var caseSensitive by mutableStateOf(false)
    var matches by mutableStateOf<List<SearchMatch>>(emptyList())
        private set
    var current by mutableIntStateOf(-1)
        private set
    var firstRow by mutableIntStateOf(0)
        private set

    /** True once a search has run for the query as it is: before that, no count is shown. */
    var searched by mutableStateOf(false)
        private set

    /** A row of the scrollback, counted from its oldest, of the match Find is on; null with none. */
    val currentRow: Int? get() = matches.getOrNull(current)?.let { it.row + firstRow }

    /** Searches [scrollback] again, keeping the match Find was on where it can. */
    fun take(scrollback: TerminalSession.Scrollback?) {
        val text = query.text
        if (scrollback == null || text.isEmpty()) {
            matches = emptyList()
            current = -1
            searched = text.isNotEmpty()
            return
        }
        val before = matches.getOrNull(current)?.let { it.copy(row = it.row + firstRow - scrollback.firstRow, endRow = it.endRow + firstRow - scrollback.firstRow) }
        val found = searchScrollback(scrollback.rows, text, caseSensitive)
        firstRow = scrollback.firstRow
        matches = found
        current = nextCurrent(found, before)
        searched = true
    }

    /** The query changed: start again from the newest match. */
    fun restart() {
        matches = emptyList()
        current = -1
        searched = false
    }

    /** Steps to the next match (towards newer output), or the previous, round the ends. */
    fun step(forward: Boolean) {
        if (matches.isEmpty()) return
        current = if (forward) (current + 1) % matches.size else (current - 1 + matches.size) % matches.size
    }

    /** "3 of 12", "No matches", or nothing before a search. */
    @Composable
    fun countText(): String? = when {
        !searched -> null
        matches.isEmpty() -> stringResource(R.string.find_none)
        matches.size >= MAX_MATCHES -> stringResource(R.string.find_count_many, current + 1, matches.size)
        else -> stringResource(R.string.find_count, current + 1, matches.size)
    }
}

/**
 * Keeps [model] searching [session] while Find is open: again a moment after the query or the case
 * switch changes (scrolling to the match), and after the program's output changes the screen
 * (staying where the person is). The matches are drawn on the pane, and the pane scrolls to the
 * one Find is on whenever that changes.
 */
@Composable
internal fun FindEffects(model: FindModel, open: Boolean, session: TerminalSession?) {
    LaunchedEffect(open, session) {
        if (!open || session == null) {
            session?.highlights = null
            return@LaunchedEffect
        }
        val output = MutableStateFlow(0)
        session.onOutput = { output.value++ }
        suspend fun searchNow() {
            val rows = withContext(Dispatchers.Default) { runCatching { session.scrollbackRows() }.getOrNull() }
            model.take(rows)
        }
        try {
            coroutineScope {
                launch {
                    snapshotFlow { model.query.text to model.caseSensitive }.collectLatest {
                        model.restart()
                        delay(FIND_TYPING_MS)
                        searchNow()
                    }
                }
                launch {
                    output.drop(1).collectLatest {
                        delay(FIND_OUTPUT_MS)
                        if (model.query.text.isNotEmpty()) searchNow()
                    }
                }
                launch {
                    snapshotFlow { Triple(model.matches, model.current, model.firstRow) }.collectLatest { (matches, current, first) ->
                        session.highlights = TerminalSession.Highlights(matches, current, first)
                    }
                }
                launch {
                    // Only a new place to be moves the pane, not output that leaves the match where it is.
                    snapshotFlow { model.currentRow }.collectLatest { row -> if (row != null) session.scrollToRow(row) }
                }
            }
        } finally {
            session.onOutput = null
            session.highlights = null
        }
    }
}

/** How long typing rests before Find searches; and how long output does, which comes in bursts. */
private const val FIND_TYPING_MS = 150L
private const val FIND_OUTPUT_MS = 400L

internal const val FIND_FIELD_TAG = "find-field"
internal const val FIND_BAR_TAG = "find-bar"

/**
 * The bar: the query, focused as it opens; the count, which TalkBack reads as it changes; the case
 * switch; previous and next; close. Return goes to the next match and Shift+Return to the previous
 * (F3 and Shift+F3 too), Escape closes, and the screen's other shortcuts still work from the field
 * ([onShortcut]).
 */
@Composable
internal fun FindBar(
    model: FindModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onShortcut: (android.view.KeyEvent) -> Boolean = { false },
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Whatever was in it is selected, so typing replaces it; Ctrl+Shift+F again does the same.
        model.query = model.query.copy(selection = TextRange(0, model.query.text.length))
        runCatching { focus.requestFocus() }
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(48.dp)
            .floating(RoundedCornerShape(24.dp)).testTag(FIND_BAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        Glyph(R.drawable.ic_search, Colors.muted, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        val hint = stringResource(R.string.find_hint)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (model.query.text.isEmpty()) {
                Text(hint, color = Colors.muted, fontSize = TextStyles.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = model.query,
                onValueChange = { model.query = it },
                singleLine = true,
                textStyle = TextStyle(color = Colors.ink, fontSize = TextStyles.body),
                cursorBrush = SolidColor(Colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { model.step(forward = true) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(FIND_FIELD_TAG)
                    .semantics { contentDescription = hint }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter, Key.F3 -> {
                                model.step(forward = !event.isShiftPressed)
                                true
                            }
                            Key.Escape -> {
                                onClose()
                                true
                            }
                            else -> onShortcut(event.nativeKeyEvent)
                        }
                    },
            )
        }
        model.countText()?.let { count ->
            Spacer(Modifier.width(8.dp))
            Text(
                count,
                color = if (model.matches.isEmpty()) Colors.muted else Colors.ink,
                fontSize = TextStyles.label,
                maxLines = 1,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val case = stringResource(R.string.find_case)
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                .toggleable(value = model.caseSensitive, role = Role.Switch) { model.caseSensitive = it }
                .semantics { contentDescription = case },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Aa",
                color = if (model.caseSensitive) Colors.onAccent else Colors.muted,
                fontSize = TextStyles.label,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (model.caseSensitive) Colors.accent else Colors.key)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        RoundButton(R.drawable.ic_arrow_up, stringResource(R.string.find_previous), { model.step(forward = false) }, tint = Colors.muted, size = 48.dp, enabled = model.matches.isNotEmpty())
        RoundButton(R.drawable.ic_arrow_down, stringResource(R.string.find_next), { model.step(forward = true) }, tint = Colors.muted, size = 48.dp, enabled = model.matches.isNotEmpty())
        RoundButton(R.drawable.ic_close, stringResource(R.string.find_close), onClose, tint = Colors.muted, size = 48.dp)
    }
}
