package me.leaver.rexpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.CheckFailure
import me.leaver.rexpocket.core.CheckReport
import me.leaver.rexpocket.core.CheckRoute
import me.leaver.rexpocket.core.CheckStep
import me.leaver.rexpocket.core.CheckTarget
import me.leaver.rexpocket.core.ConnectionCheck
import me.leaver.rexpocket.core.FileUpload
import me.leaver.rexpocket.core.SshCredentials
import me.leaver.rexpocket.core.StepResult
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.diagnostics.Diagnostics

/**
 * The host dialog's Test connection: runs [ConnectionCheck] on the host as it is typed, one at a
 * time, and keeps the latest report for the dialog. It writes nothing - not the host, not a host
 * key: a key the person trusts during the test is the dialog's to carry into Save. See
 * docs/design/connection-test.md.
 */
class HostTester(
    private val scope: CoroutineScope,
    /** The tailnet route, through the app's own node seam; only asked for a tailnet host. */
    private val route: suspend (host: String) -> CheckRoute,
) {
    private val _report = MutableStateFlow<CheckReport?>(null)
    val report: StateFlow<CheckReport?> = _report

    /** The host's other addresses, each with its own check once the main one has run (null: not yet). */
    private val _others = MutableStateFlow<List<Pair<String, CheckReport?>>>(emptyList())
    val others: StateFlow<List<Pair<String, CheckReport?>>> = _others
    private var job: Job? = null

    /** The target the report is for, so the dialog can tell when what is typed moved on. */
    @Volatile var tested: CheckTarget? = null
        private set

    /**
     * Checks [target], then each of [others] (an address's key and its target) in the order a
     * connection tries them, one at a time. An SSH address is checked against the same host key.
     */
    fun start(target: CheckTarget, others: List<Pair<String, CheckTarget>> = emptyList()) {
        job?.cancel()
        tested = target
        _report.value = null
        _others.value = others.map { it.first to null }
        Diagnostics.event(TAG, "test ${if (target.viaSsh) "ssh" else if (target.plaintext) "ws" else "wss"} tailnet=${target.viaTailnet} others=${others.size}")
        job = scope.launch {
            val report = ConnectionCheck(target, route = { route(target.host) }).run { _report.value = it }
            // Steps and their outcome only: never an address's credentials.
            Diagnostics.event(TAG, "test: " + report.steps.joinToString { (s, r) -> "$s ${outcome(r)}" })
            others.forEachIndexed { i, (key, other) ->
                val checked = ConnectionCheck(other, route = { route(other.host) }).run { r ->
                    _others.value = _others.value.toMutableList().also { it[i] = key to r }
                }
                Diagnostics.event(TAG, "test other ${i + 1}: " + checked.steps.joinToString { (s, r) -> "$s ${outcome(r)}" })
            }
        }
    }

    /** Stops a test under way; its running step reads Stopped. */
    fun cancel() {
        job?.cancel()
    }

    /** Forgets the report: the dialog closed, or what it tested was changed. */
    fun clear() {
        job?.cancel()
        job = null
        tested = null
        _report.value = null
        _others.value = emptyList()
    }

    companion object {
        const val TAG = "RexPocketCheck"

        private fun outcome(r: StepResult): String = when (r) {
            is StepResult.Passed -> "ok"
            is StepResult.Failed -> r.failure.name
            StepResult.NotReached -> "-"
            StepResult.Running -> "running"
            StepResult.Waiting -> "waiting"
        }
    }
}

/** What the dialog has typed, as the check takes it: the same host Save would write. */
internal fun SavedHost.checkTarget(): CheckTarget = CheckTarget(
    host = host,
    port = port,
    plaintext = plaintext,
    sshUser = sshUser.takeIf { viaSsh },
    credentials = SshCredentials(
        password = sshPassword.takeIf { it.isNotEmpty() },
        privateKey = sshPrivateKey.takeIf { it.isNotBlank() },
        passphrase = sshPassphrase.takeIf { it.isNotEmpty() },
    ),
    hostKey = sshHostKey.takeIf { it.isNotBlank() },
    viaTailnet = viaTailscale,
)

/**
 * The other addresses a connection would try, as checks: each with the host's credentials and its
 * one trusted key; addresses a connection skips are left out, as they are never dialed.
 */
internal fun SavedHost.otherCheckTargets(): List<Pair<String, CheckTarget>> {
    val main = checkTarget()
    return copy(tryOthers = true).routes().filter { !it.main }.map { r ->
        r.key to main.copy(host = r.endpoint.host, port = r.endpoint.port, plaintext = r.endpoint.plaintext, sshUser = r.endpoint.sshUser, viaTailnet = r.viaTailnet)
    }
}

internal const val HOST_CHECK_TAG = "host-check"
internal const val OTHER_CHECKS_TAG = "other-checks"

/**
 * Each other address's check, one line each: it works, why it does not, or that it shows another
 * host key than the main address did ([mainKey]), which a connection would never take.
 */
@Composable
internal fun OtherChecks(others: List<Pair<String, CheckReport?>>, mainKey: String?) {
    if (others.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag(OTHER_CHECKS_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.check_other_addresses), color = Colors.ink, fontSize = TextStyles.label, fontWeight = FontWeight.Medium)
        for ((key, report) in others) {
            val failed = report?.steps?.map { it.second }?.filterIsInstance<StepResult.Failed>()?.firstOrNull()
            val seen = report?.seenHostKey
            val (text, color) = when {
                report == null -> stringResource(R.string.check_other_waiting) to Colors.muted
                !report.finished -> stringResource(R.string.check_state_running) to Colors.accent
                report.passed -> stringResource(R.string.check_other_works) to Colors.ok
                seen != null && mainKey != null && seen != mainKey -> stringResource(R.string.check_other_other_key, seen) to Colors.danger
                failed != null && failed.failure == CheckFailure.HostKeyUnknown && seen != null && seen == mainKey -> stringResource(R.string.check_other_same_key) to Colors.muted
                failed != null -> failureReason(failed, report) to Colors.danger
                else -> stringResource(R.string.check_state_failed) to Colors.danger
            }
            Text(
                "$key: $text",
                color = color,
                fontSize = TextStyles.label,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * The Test connection part of the host dialog: the button (Stop while it runs), each step with
 * what it found or why it failed and what to do, and for an SSH host key that has not been
 * trusted, or changed, the key itself with a button to trust it for the test.
 */
@Composable
internal fun HostCheckPanel(
    report: CheckReport?,
    running: Boolean,
    enabled: Boolean,
    /** The key pinned now, to show beside the one the machine showed when they differ. */
    pinned: String?,
    /** A key trusted during this dialog's tests; Save keeps it. */
    trusted: String?,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onTrust: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(HOST_CHECK_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (running) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.check_cancel), color = Colors.accent)
            }
        } else {
            TextButton(onClick = onRun, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    stringResource(if (report == null) R.string.check_run else R.string.check_again),
                    color = if (enabled) Colors.accent else Colors.muted,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (report == null) {
            Text(stringResource(R.string.check_saves_nothing), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
            return@Column
        }
        report.steps.forEach { (step, result) -> CheckRow(step, result, report) }
        if (report.passed) {
            Text(
                stringResource(R.string.check_all_passed),
                color = Colors.ok,
                fontSize = TextStyles.label,
                lineHeight = labelLeading,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val seen = report.seenHostKey
        val key = report.of(CheckStep.HostKey) as? StepResult.Failed
        if (seen != null && key != null && (key.failure == CheckFailure.HostKeyUnknown || key.failure == CheckFailure.HostKeyChanged)) {
            val changed = key.failure == CheckFailure.HostKeyChanged
            if (changed && pinned != null) {
                Text(stringResource(R.string.check_key_pinned), color = Colors.muted, fontSize = TextStyles.label)
                SelectionContainer { Text(pinned, color = Colors.ink, fontSize = TextStyles.label, fontFamily = FontFamily.Monospace) }
            }
            Text(stringResource(R.string.check_key_seen), color = Colors.muted, fontSize = TextStyles.label)
            SelectionContainer { Text(seen, color = if (changed) Colors.danger else Colors.ink, fontSize = TextStyles.label, fontFamily = FontFamily.Monospace) }
            TextButton(onClick = { onTrust(seen) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    stringResource(if (changed) R.string.check_trust_changed else R.string.check_trust),
                    color = if (changed) Colors.danger else Colors.accent,
                )
            }
        }
        if (trusted != null) {
            Text(stringResource(R.string.check_trusted_saved), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
        }
    }
}

/** One step: its name and state, then what it found, or why it failed and what to do about it. */
@Composable
private fun CheckRow(step: CheckStep, result: StepResult, report: CheckReport) {
    val name = stepName(step)
    val (state, color) = when (result) {
        is StepResult.Passed -> stringResource(R.string.check_state_passed) to Colors.ok
        is StepResult.Failed -> stringResource(
            if (result.failure == CheckFailure.Canceled) R.string.check_state_stopped else R.string.check_state_failed,
        ) to if (result.failure == CheckFailure.Canceled) Colors.muted else Colors.danger
        StepResult.Running -> stringResource(R.string.check_state_running) to Colors.accent
        StepResult.Waiting -> stringResource(R.string.check_state_waiting) to Colors.muted
        StepResult.NotReached -> stringResource(R.string.check_state_not_reached) to Colors.muted
    }
    val found = (result as? StepResult.Passed)?.let { passedDetail(step, it, report) }
    val failed = result as? StepResult.Failed
    val why = failed?.takeIf { it.failure != CheckFailure.Canceled }?.let { failureReason(it, report) }
    val fix = failed?.takeIf { it.failure != CheckFailure.Canceled }?.let { failureHint(it.failure) }
    // One TalkBack stop per step: its name, its state, and the reason and fix when it failed.
    val spoken = listOfNotNull(name, state, found, why, fix).joinToString(". ")
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = spoken
                if (result !is StepResult.Waiting) liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state, color = color, fontSize = TextStyles.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp))
            Text(name, color = Colors.ink, fontSize = TextStyles.body, modifier = Modifier.weight(1f))
        }
        found?.let { Text(it, color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading) }
        why?.let { Text(it, color = Colors.danger, fontSize = TextStyles.label, lineHeight = labelLeading) }
        fix?.let { Text(it, color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading) }
        failed?.detail?.takeIf { failed.failure in DETAILED }?.let {
            Text(it, color = Colors.muted, fontSize = TextStyles.label, fontFamily = FontFamily.Monospace, maxLines = 3)
        }
    }
}

/** The failures whose own words help: the rest are said in full by the reason. */
private val DETAILED = setOf(CheckFailure.Handshake, CheckFailure.Other, CheckFailure.Tls, CheckFailure.Unreachable, CheckFailure.TailnetDown)

@Composable
private fun stepName(step: CheckStep): String = stringResource(
    when (step) {
        CheckStep.Tailnet -> R.string.check_step_tailnet
        CheckStep.Resolve -> R.string.check_step_resolve
        CheckStep.Connect -> R.string.check_step_connect
        CheckStep.HostKey -> R.string.check_step_host_key
        CheckStep.SignIn -> R.string.check_step_sign_in
        CheckStep.Rex -> R.string.check_step_rex
        CheckStep.Api -> R.string.check_step_api
    },
)

@Composable
private fun passedDetail(step: CheckStep, r: StepResult.Passed, report: CheckReport): String? = when (step) {
    CheckStep.Api -> {
        val revision = report.apiRevision ?: 0
        if (revision < FileUpload.API_REVISION) stringResource(R.string.check_api_no_upload, revision, FileUpload.API_REVISION)
        else stringResource(R.string.check_api, revision)
    }
    CheckStep.Rex -> null
    else -> r.detail
}

@Composable
private fun failureReason(f: StepResult.Failed, report: CheckReport): String = when (f.failure) {
    CheckFailure.TailnetNotJoined -> stringResource(R.string.check_fail_tailnet_not_joined)
    CheckFailure.TailnetDown -> stringResource(R.string.check_fail_tailnet_down)
    CheckFailure.UnknownHost -> stringResource(R.string.check_fail_unknown_host)
    CheckFailure.Refused -> stringResource(R.string.check_fail_refused)
    CheckFailure.Unreachable -> stringResource(R.string.check_fail_unreachable)
    CheckFailure.TimedOut -> stringResource(R.string.check_fail_timed_out)
    CheckFailure.HostKeyUnknown -> stringResource(R.string.check_fail_key_unknown)
    CheckFailure.HostKeyChanged -> stringResource(R.string.check_fail_key_changed)
    CheckFailure.Handshake -> stringResource(R.string.check_fail_handshake)
    CheckFailure.NoCredentials -> stringResource(R.string.check_fail_no_credentials)
    CheckFailure.KeyUnreadable -> stringResource(R.string.check_fail_key_unreadable)
    CheckFailure.SignInRefused -> stringResource(R.string.check_fail_sign_in)
    CheckFailure.RexMissing -> stringResource(R.string.check_fail_rex_missing)
    CheckFailure.NotRex -> stringResource(R.string.check_fail_not_rex)
    CheckFailure.Tls -> stringResource(R.string.check_fail_tls)
    CheckFailure.RexRefused -> stringResource(R.string.check_fail_rex_refused)
    CheckFailure.RexNoAnswer -> stringResource(R.string.check_fail_rex_no_answer)
    CheckFailure.ApiTooOld -> stringResource(R.string.check_fail_api_old, report.apiRevision ?: 0, ConnectionCheck.MIN_API_REVISION)
    CheckFailure.Canceled -> stringResource(R.string.check_state_stopped)
    CheckFailure.Other -> stringResource(R.string.check_fail_other)
}

@Composable
private fun failureHint(failure: CheckFailure): String? = when (failure) {
    CheckFailure.TailnetNotJoined -> R.string.check_fix_tailnet_not_joined
    CheckFailure.TailnetDown -> R.string.check_fix_tailnet_down
    CheckFailure.UnknownHost -> R.string.check_fix_unknown_host
    CheckFailure.Refused -> R.string.check_fix_refused
    CheckFailure.Unreachable -> R.string.check_fix_unreachable
    CheckFailure.TimedOut -> R.string.check_fix_timed_out
    CheckFailure.HostKeyUnknown -> R.string.check_fix_key_unknown
    CheckFailure.HostKeyChanged -> R.string.check_fix_key_changed
    CheckFailure.Handshake -> R.string.check_fix_handshake
    CheckFailure.NoCredentials -> R.string.check_fix_no_credentials
    CheckFailure.KeyUnreadable -> R.string.check_fix_key_unreadable
    CheckFailure.SignInRefused -> R.string.check_fix_sign_in
    CheckFailure.RexMissing -> R.string.check_fix_rex_missing
    CheckFailure.NotRex -> R.string.check_fix_not_rex
    CheckFailure.Tls -> R.string.check_fix_tls
    CheckFailure.RexRefused -> R.string.check_fix_rex_refused
    CheckFailure.RexNoAnswer -> R.string.check_fix_rex_no_answer
    CheckFailure.ApiTooOld -> R.string.check_fix_api_old
    CheckFailure.Other -> R.string.check_fix_other
    CheckFailure.Canceled -> null
}?.let { stringResource(it) }
