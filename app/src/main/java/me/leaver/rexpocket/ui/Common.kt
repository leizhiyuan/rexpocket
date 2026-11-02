package me.leaver.rexpocket.ui

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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column


import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

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

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role


import androidx.compose.ui.text.TextStyle

import androidx.compose.ui.text.font.FontWeight


import androidx.compose.ui.unit.dp

import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.Problem
import me.leaver.rexpocket.core.Trouble
import android.content.Intent
import android.net.Uri
import me.leaver.rexpocket.R
import kotlinx.coroutines.delay

// Pieces several screens use: colour aliases, list rows, fields, header buttons, error text.

/** The one action at the foot of a screen: full width, in the action colour, on the paper. */
@Composable
internal fun PrimaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(Radius.card))
            .background(if (enabled) Colors.accent else Colors.line)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Colors.onAccent else Colors.muted,
            fontSize = TextStyles.body,
            fontWeight = FontWeight.SemiBold,
        )
    }
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
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).border(1.dp, Colors.line, RoundedCornerShape(Radius.chip))
                .padding(horizontal = 12.dp, vertical = 12.dp)
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
