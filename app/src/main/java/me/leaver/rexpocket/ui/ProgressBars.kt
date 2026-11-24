package me.leaver.rexpocket.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.PaneProgress
import me.leaver.rexpocket.core.ProgressState

// A program's progress bar (OSC 9;4, which Rex forwards): a thin line under what it belongs to - the
// terminal's top bar, a pane's segment of the pane switcher, a tab's row on the home. See
// docs/design/terminal-controls.md, "Progress".

/** The colour of a bar: the action colour at work, red for an error, amber paused, as a status dot is. */
internal fun progressTint(progress: PaneProgress): Color = when (progress.state) {
    ProgressState.Normal, ProgressState.Indeterminate -> Colors.accent
    ProgressState.Error -> Colors.danger
    ProgressState.Paused -> Colors.amber
}

/** What TalkBack says for a bar. */
@Composable
internal fun progressWords(progress: PaneProgress): String {
    val percent = progress.percent
    return when (progress.state) {
        ProgressState.Normal -> stringResource(R.string.progress_normal, percent ?: 0)
        ProgressState.Indeterminate -> stringResource(R.string.progress_busy)
        ProgressState.Error -> if (percent != null) stringResource(R.string.progress_error, percent) else stringResource(R.string.progress_error_plain)
        ProgressState.Paused -> if (percent != null) stringResource(R.string.progress_paused, percent) else stringResource(R.string.progress_paused_plain)
    }
}

/**
 * The bar itself, [height] tall and as wide as it is given: a track and the done part, or a moving
 * piece for work with no percent (and for an error or a pause without one, still, at full width).
 * TalkBack reads it as a progress bar with its words; [merged] leaves it out, for a row that says
 * [progressWords] in its own state.
 */
@Composable
internal fun ProgressLine(progress: PaneProgress, modifier: Modifier = Modifier, height: Dp = 3.dp, merged: Boolean = false) {
    val tint = progressTint(progress)
    val words = if (merged) "" else progressWords(progress)
    val fraction = progress.fraction
    val moving = progress.state == ProgressState.Indeterminate
    val sweep = if (moving) {
        val transition = rememberInfiniteTransition(label = "progress")
        val at by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
        at
    } else {
        0f
    }
    Canvas(
        modifier.fillMaxWidth().height(height).testTag(PROGRESS_TAG).then(
            if (merged) {
                Modifier
            } else {
                Modifier.semantics {
                    contentDescription = words
                    progressBarRangeInfo = if (fraction == null) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(fraction, 0f..1f)
                }
            },
        ),
    ) {
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(tint.copy(alpha = 0.22f), cornerRadius = radius)
        when {
            moving -> {
                val piece = size.width * 0.3f
                val left = -piece + (size.width + piece) * sweep
                drawRoundRect(tint, topLeft = Offset(left.coerceAtLeast(0f), 0f), size = Size((left + piece).coerceAtMost(size.width) - left.coerceAtLeast(0f), size.height), cornerRadius = radius)
            }
            fraction == null -> drawRoundRect(tint, cornerRadius = radius)
            fraction > 0f -> drawRoundRect(tint, size = Size(size.width * fraction, size.height), cornerRadius = radius)
        }
    }
}

/** The test tag every progress bar carries. */
internal const val PROGRESS_TAG = "progress-bar"
