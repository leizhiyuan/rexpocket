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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

import androidx.compose.ui.text.font.FontWeight


import androidx.compose.ui.unit.dp

import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.Problem
import me.leaver.rexpocket.core.Trouble
import me.leaver.rexpocket.core.APPROVAL
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
internal fun Field(
    hint: String,
    value: String,
    autoFocus: Boolean = false,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    /** What an empty field says inside the box, so it is clear what belongs in it. */
    placeholder: String? = null,
    /**
     * The keyboard for what goes in: [KeyboardType.Uri] for an address, [KeyboardType.Ascii] for a
     * user name. A password field always gets the password keyboard.
     */
    keyboardType: KeyboardType = KeyboardType.Text,
    /** False for what is not prose - an address, a user name - which a keyboard must not "correct". */
    autoCorrect: Boolean = true,
    /** The keyboard's action key: [ImeAction.Next] moves to the next field, [ImeAction.Done] runs [onDone]. */
    imeAction: ImeAction = ImeAction.Default,
    onDone: (() -> Unit)? = null,
    /** This field, for another field's [next]. */
    focusRequester: FocusRequester? = null,
    /** Where [ImeAction.Next] goes when it is not simply the next thing that takes focus, such as past a row of buttons. */
    next: FocusRequester? = null,
    onChange: (String) -> Unit,
) {
    // With autoFocus the field opens focused with its text selected, so a rename can be typed over.
    var field by remember { mutableStateOf(TextFieldValue(value, if (autoFocus) TextRange(0, value.length) else TextRange(value.length))) }
    val focus = focusRequester ?: remember { FocusRequester() }
    val currentDone = rememberUpdatedState(onDone)
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
        // The hint is the field's name for TalkBack too: it is said with the field, not as a line of its own.
        Text(hint, color = Colors.muted, fontSize = TextStyles.label, modifier = Modifier.clearAndSetSemantics {})
        // The placeholder is painted behind the field, which stays the thing that takes the tap.
        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            if (placeholder != null && field.text.isEmpty()) {
                Text(
                    placeholder,
                    color = Colors.muted,
                    fontSize = TextStyles.body,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 13.dp),
                )
            }
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    onChange(it.text)
                },
                textStyle = TextStyle(color = Colors.ink, fontSize = TextStyles.body),
                cursorBrush = SolidColor(Colors.accent),
                singleLine = singleLine,
                minLines = minLines,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    autoCorrectEnabled = autoCorrect && !password,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(
                    // The field keeps the actions it was first given, so Done calls the newest onDone:
                    // a dialog's save reads what was typed since.
                    onDone = if (onDone != null) { { currentDone.value?.invoke() } } else null,
                    onNext = next?.let { field -> { field.requestFocus() } },
                ),
                modifier = Modifier.fillMaxWidth().border(1.dp, Colors.line, RoundedCornerShape(Radius.chip))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .focusRequester(focus)
                    .semantics { contentDescription = hint },
            )
        }
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

/**
 * What went wrong, in the app's words; Rex's own message only where it says something specific.
 * [network] is the tailnet's backend, Tailscale or Netbird, for the problems that are about it.
 */
@Composable
internal fun problemText(problem: Problem, endpoint: String, network: String = "tailnet"): String = when (problem.trouble) {
    Trouble.TailnetNeedsLogin ->
        if (problem.detail == APPROVAL) stringResource(R.string.problem_tailnet_needs_approval, network)
        else stringResource(R.string.problem_tailnet_needs_login, network, endpointHost(endpoint))
    Trouble.TailnetPeerUnknown -> stringResource(R.string.problem_tailnet_peer_unknown, network, endpointHost(endpoint))
    Trouble.TailnetPeerOffline -> problem.detail?.toLongOrNull()?.let { seen ->
        stringResource(R.string.problem_tailnet_peer_offline_seen, network, endpointHost(endpoint), relativeTime(seen))
    } ?: stringResource(R.string.problem_tailnet_peer_offline, network, endpointHost(endpoint))
    Trouble.RexNotListening ->
        stringResource(R.string.problem_rex_not_listening, endpointHost(endpoint), problem.detail ?: endpoint.substringAfterLast(':'))
    Trouble.Unreachable -> stringResource(R.string.problem_unreachable, endpoint)
    Trouble.TimedOut -> stringResource(R.string.problem_timed_out, endpoint)
    Trouble.Dropped -> stringResource(R.string.problem_dropped)
    Trouble.NotReady -> stringResource(R.string.problem_not_ready)
    Trouble.TailnetNotJoined -> stringResource(R.string.error_join_tailnet_first)
    Trouble.Tailnet -> stringResource(R.string.problem_tailnet, problem.detail.orEmpty())
    Trouble.Rejected -> stringResource(R.string.problem_rejected, problem.detail.orEmpty())
    Trouble.SshHostKeyUnknown -> stringResource(R.string.problem_ssh_host_key_unknown)
    Trouble.SshHostKeyChanged -> stringResource(R.string.problem_ssh_host_key_changed)
    Trouble.SshAuth -> stringResource(R.string.problem_ssh_auth, problem.detail.orEmpty())
    Trouble.SshCommand -> stringResource(R.string.problem_ssh_command, problem.detail.orEmpty())
    Trouble.Other -> problem.detail ?: stringResource(R.string.error_operation_failed)
}

/** Why a file did not go, in the app's words; what Rex said as it said it. */
@Composable
internal fun uploadErrorText(error: UploadError): String = when (error) {
    UploadError.Gone -> stringResource(R.string.upload_error_gone)
    UploadError.TooLarge -> stringResource(R.string.upload_error_too_large)
    UploadError.Empty -> stringResource(R.string.upload_error_empty)
    UploadError.Unreadable -> stringResource(R.string.upload_error_unreadable)
    UploadError.Failed -> stringResource(R.string.upload_error_failed)
    is UploadError.Said -> error.message
}

@Composable
internal fun tailnetFailureText(failure: TailnetFailure): String =
    failure.detail ?: stringResource(R.string.tailnet_start_failed)

/** The host of a `host:port` or `user@host:port` label, an IPv6 literal's brackets kept. */
internal fun endpointHost(label: String): String {
    val endpoint = label.substringAfterLast('@')
    val colon = endpoint.lastIndexOf(':')
    if (colon <= 0 || endpoint.substring(colon + 1).any { !it.isDigit() }) return endpoint
    val host = endpoint.substring(0, colon)
    return if (':' in host && !host.startsWith('[')) endpoint else host
}

/** [epochMs] as "2 hours ago", in the phone's language. */
internal fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
        epochMs, now, android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

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
internal fun openLink(context: Context, url: String, copied: Int = R.string.tailscale_login_copied) {
    val opened = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
    if (!opened) {
        clipboardSet(context, url)
        toast(context, context.getString(copied))
    }
}

internal fun clipboardSet(context: Context, text: String) {
    val clip = context.getSystemService(ClipboardManager::class.java) ?: return
    clip.setPrimaryClip(ClipData.newPlainText("rex", text))
}

/** Offers [text] to another app through the platform's share sheet. */
internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

internal fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
