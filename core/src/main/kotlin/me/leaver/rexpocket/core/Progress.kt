package me.leaver.rexpocket.core

import org.json.JSONObject

// A program's progress bar: OSC 9;4 (ConEmu's, which Ghostty, Windows Terminal and others show),
// forwarded by Rex as the terminal's `progress_report` block event. See docs/design/terminal-controls.md,
// "Progress".

/** The kinds of progress OSC 9;4 can report, other than its removal (state 0). */
enum class ProgressState {
    /** State 1: work under way, [PaneProgress.percent] done. */
    Normal,

    /** State 2: something went wrong; a percent may come with it. */
    Error,

    /** State 3: busy, with no way to say how far along. */
    Indeterminate,

    /** State 4: paused, or a warning; a percent may come with it. */
    Paused,
}

/** What a pane's progress bar shows: its [state] and, when the program said, how far along it is (0-100). */
data class PaneProgress(val state: ProgressState, val percent: Int? = null) {
    /** The bar's fill, 0 to 1, or null for one that only says it is busy. */
    val fraction: Float? get() = if (state == ProgressState.Indeterminate) null else percent?.let { it / 100f }
}

/**
 * Rex's `progress_report` payload, `{state, progress}`: `set`, `error`, `indeterminate` or `pause`
 * as a [PaneProgress], null for `remove`. A state Rex adds later is read as busy, with its percent
 * if it has one, rather than dropped: a bar that says something is going on is still true. Rex
 * allows percents to 255 (the program's number as it wrote it); a bar stops at 100.
 */
fun parseProgressReport(body: JSONObject): PaneProgress? {
    val percent = if (body.has("progress") && !body.isNull("progress")) body.optInt("progress").coerceIn(0, 100) else null
    return when (body.optString("state")) {
        "remove" -> null
        "set" -> PaneProgress(ProgressState.Normal, percent ?: 0)
        "error" -> PaneProgress(ProgressState.Error, percent)
        "indeterminate" -> PaneProgress(ProgressState.Indeterminate)
        "pause" -> PaneProgress(ProgressState.Paused, percent)
        else -> PaneProgress(if (percent == null) ProgressState.Indeterminate else ProgressState.Normal, percent)
    }
}

/**
 * What OSC 9;4 `state;value` says, as ConEmu defined it: 0 removes, 1 sets the percent, 2 is an
 * error, 3 indeterminate, 4 paused. Rex reads the sequence itself; this is for the tests and the
 * fake Rex, which speak in sequences, and maps to Rex's names with [rexProgressState].
 */
fun oscProgress(state: Int, value: Int? = null): PaneProgress? = when (state) {
    0 -> null
    1 -> PaneProgress(ProgressState.Normal, (value ?: 0).coerceIn(0, 100))
    2 -> PaneProgress(ProgressState.Error, value?.coerceIn(0, 100))
    3 -> PaneProgress(ProgressState.Indeterminate)
    4 -> PaneProgress(ProgressState.Paused, value?.coerceIn(0, 100))
    else -> null
}

/** Rex's word for an OSC 9;4 state, as its `progress_report` sends it. */
fun rexProgressState(osc: Int): String = when (osc) {
    1 -> "set"
    2 -> "error"
    3 -> "indeterminate"
    4 -> "pause"
    else -> "remove"
}

/**
 * The progress a tab shows: its focused pane's, else the most pressing of its other panes' - an
 * error, then a pause, then work with a percent, then work without one - since a tab is one row
 * and one bar.
 */
fun tabProgress(window: WindowInfo, progress: Map<String, PaneProgress>): PaneProgress? {
    if (progress.isEmpty()) return null
    window.focusedBlock()?.id?.let { focused -> progress[focused]?.let { return it } }
    return window.blocks.mapNotNull { progress[it.id] }.minByOrNull { it.state.rank() }
}

private fun ProgressState.rank(): Int = when (this) {
    ProgressState.Error -> 0
    ProgressState.Paused -> 1
    ProgressState.Normal -> 2
    ProgressState.Indeterminate -> 3
}

/** One tab with a progress bar, for the progress notification: where it is, the pane it is from, and what it shows. */
data class TabProgress(val sessionId: String, val windowId: String, val blockId: String, val progress: PaneProgress)

/** Every tab of [views] with a progress bar, in the views' order. */
fun tabsWithProgress(views: Collection<SessionView>, progress: Map<String, PaneProgress>): List<TabProgress> {
    if (progress.isEmpty()) return emptyList()
    return views.flatMap { view ->
        view.windows.mapNotNull { window ->
            val shown = tabProgress(window, progress) ?: return@mapNotNull null
            val block = window.focusedBlock()?.id?.takeIf { progress[it] == shown }
                ?: window.blocks.first { progress[it.id] == shown }.id
            TabProgress(view.sessionId, window.id, block, shown)
        }
    }
}

/**
 * How long a bar stays with no new report. A program that dies or forgets to remove its bar
 * would otherwise leave it for good; Ghostty's desktop app drops a bar after 15 s without a report,
 * and the phone does the same.
 */
const val PROGRESS_STALE_MS = 15_000L
