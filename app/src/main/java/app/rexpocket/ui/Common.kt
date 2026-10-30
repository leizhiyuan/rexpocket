package app.rexpocket.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rexpocket.core.Phase
import app.rexpocket.core.PocketState
import app.rexpocket.core.Problem
import app.rexpocket.core.Trouble
import android.content.Intent
import android.net.Uri
import app.rexpocket.R
import kotlinx.coroutines.delay

// Pieces several screens use: colour aliases, list rows, fields, header buttons, error text.

/**
 * One row of a list, whether it lists hosts or sessions: the whole card opens the thing, and the
 * label on the right only becomes its own target when it does something else, such as editing.
 */
@Composable
internal fun EntryCard(
    title: String,
    detail: String,
    tags: List<String>,
    trailing: String,
    onOpen: () -> Unit,
    onTrailing: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface).clickable(onClick = onOpen)
            .padding(horizontal = Space.l, vertical = Space.m + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Colors.ink, fontSize = TextStyles.body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(Space.xs))
                // An address is a figure, so it is set in the mono face; everything else stays body.
                Text(
                    detail,
                    color = Colors.muted,
                    fontSize = TextStyles.label,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(Space.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    tags.forEach { Tag(it) }
                }
            }
        }
        Spacer(Modifier.width(Space.m))
        Text(
            trailing,
            color = Colors.accent,
            fontSize = TextStyles.label,
            modifier = (if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier)
                .padding(horizontal = Space.s - 2.dp, vertical = Space.s - 2.dp),
        )
    }
}

/** A one-word fact about a row: plaintext, TLS, Tailscale. A hairline, not a coloured pill. */
@Composable
internal fun Tag(text: String) {
    Text(
        text,
        color = Colors.muted,
        fontSize = TextStyles.label,
        modifier = Modifier.clip(RoundedCornerShape(Space.xs)).border(1.dp, Colors.line, RoundedCornerShape(Space.xs))
            .padding(horizontal = Space.s - 2.dp, vertical = 2.dp),
    )
}

@Composable
internal fun Field(hint: String, value: String, autoFocus: Boolean = false, onChange: (String) -> Unit) {
    // With autoFocus the field opens focused with its text selected, so a rename can be typed over.
    var field by remember { mutableStateOf(TextFieldValue(value, if (autoFocus) TextRange(0, value.length) else TextRange(value.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (autoFocus) {
        LaunchedEffect(Unit) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    // Written outside composition: setting state while composing makes the field lose a tap's focus.
    LaunchedEffect(value) {
        if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    }
    Column {
        Text(hint, color = Colors.muted, fontSize = TextStyles.label)
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                onChange(it.text)
            },
            textStyle = TextStyle(color = Colors.ink, fontSize = TextStyles.body),
            cursorBrush = SolidColor(Colors.accent),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).border(1.dp, Colors.line, RoundedCornerShape(8.dp)).padding(10.dp)
                .focusRequester(focus),
        )
    }
}

/**
 * Errors that survive a moment. A tailnet dial can fail once while the node comes up and be fine
 * 400 ms later; flashing that in red teaches the user to ignore the red.
 */
@Composable
internal fun rememberSettledProblem(phase: Phase, problem: Problem?): Problem? {
    var settled by remember { mutableStateOf<Problem?>(null) }
    LaunchedEffect(problem, phase) {
        if (problem == null) {
            settled = null
        } else if (phase == Phase.Failed) {
            settled = problem
        } else {
            delay(2_500)
            settled = problem
        }
    }
    return settled
}

/** What went wrong, in the app's words; Rex's own message only where it says something specific. */
@Composable
internal fun problemText(problem: Problem, endpoint: String): String = when (problem.trouble) {
    Trouble.Unreachable -> stringResource(R.string.problem_unreachable, endpoint)
    Trouble.TimedOut -> stringResource(R.string.problem_timed_out, endpoint)
    Trouble.Dropped -> stringResource(R.string.problem_dropped)
    Trouble.NotReady -> stringResource(R.string.problem_not_ready)
    Trouble.TailnetNotJoined -> stringResource(R.string.error_join_tailnet_first)
    Trouble.Tailnet -> stringResource(R.string.problem_tailnet, problem.detail.orEmpty())
    Trouble.Rejected -> stringResource(R.string.problem_rejected, problem.detail.orEmpty())
    Trouble.Other -> problem.detail ?: stringResource(R.string.error_operation_failed)
}

/** The smallest area a tap target takes, as Android's accessibility guidance asks. */
internal val MIN_TOUCH = 48.dp

/** A text button in a header: at least 48dp to hit, and [spoken] read out when the label is a glyph. */
@Composable
internal fun HeaderButton(
    label: String,
    color: Color = Colors.accent,
    spoken: String? = null,
    size: TextUnit = TextStyles.body,
    onClick: () -> Unit,
) {
    Box(
        Modifier.sizeIn(minWidth = MIN_TOUCH, minHeight = MIN_TOUCH)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = size)
    }
}

@Composable
internal fun phaseText(state: PocketState): String = when (state.phase) {
    Phase.Idle -> stringResource(R.string.status_idle)
    Phase.Connecting -> stringResource(R.string.status_connecting, state.endpointLabel)
    Phase.Live -> stringResource(if (state.resumed) R.string.status_resumed else R.string.status_live)
    Phase.Reconnecting -> stringResource(R.string.status_reconnecting)
    Phase.Failed -> stringResource(R.string.status_failed)
}

internal fun clipboard(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java) ?: return null
    return clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

/** Opens a link in the browser; a phone without one gets the link on the clipboard instead. */
internal fun openLink(context: Context, url: String) {
    val opened = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
    if (!opened) {
        clipboardSet(context, url)
        toast(context, context.getString(R.string.tailscale_login_copied))
    }
}

internal fun clipboardSet(context: Context, text: String) {
    val clip = context.getSystemService(ClipboardManager::class.java) ?: return
    clip.setPrimaryClip(ClipData.newPlainText("rex", text))
}

internal fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
