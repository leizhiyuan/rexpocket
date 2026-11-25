package me.leaver.rexpocket.ui

import me.leaver.rexpocket.ui.guide.GuideSpot
import me.leaver.rexpocket.ui.guide.guideTarget
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.SecurityPrefs
import me.leaver.rexpocket.diagnostics.Diagnostics

// Settings › Security: the app lock, the screen that stands in for the app while it is locked, and
// the screenshot block. See docs/design/app-lock.md.

internal const val LOCK_SCREEN_TAG = "app-lock-screen"
internal const val UNLOCK_TAG = "app-unlock"
internal const val SECURITY_LOCK_TAG = "security-lock"
internal const val SECURITY_SCREENSHOTS_TAG = "security-screenshots"

/**
 * What the phone's prompt said: the person confirmed, the person put it away (Cancel, Back, a tap
 * outside), or it could not ask at all, and why, in the phone's words where it gave any.
 */
internal sealed interface AuthResult {
    data object Confirmed : AuthResult

    data object Cancelled : AuthResult

    data class Failed(val reason: String, val cause: Throwable? = null) : AuthResult
}

/** Asks the phone who is there: its fingerprint, face or screen lock. */
internal fun interface Authenticate {
    fun ask(onResult: (AuthResult) -> Unit)
}

internal object AppLockGate {
    /** Whether the phone has a screen lock: without one there is nothing for the app lock to ask. */
    fun deviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Why the lock cannot be turned on on this phone, in words for the person; null when it can. */
    fun problem(context: Context): String? =
        if (deviceSecure(context)) null else context.getString(R.string.security_lock_no_screen_lock)

    /**
     * The prompt's error as a result: the person putting it away is a no; anything else is the
     * phone failing to ask, said in its own words ([errString]).
     */
    fun resultOf(errorCode: Int, errString: CharSequence?): AuthResult = when (errorCode) {
        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BIOMETRIC_ERROR_NEGATIVE_BUTTON -> AuthResult.Cancelled
        else -> AuthResult.Failed(errString?.toString()?.takeIf { it.isNotBlank() } ?: "error $errorCode")
    }

    /** BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON, from Android 10's SDK; the prompt here has no such button. */
    private const val BIOMETRIC_ERROR_NEGATIVE_BUTTON = 13

    /**
     * The framework's biometric prompt (Android 10 on), with the screen lock as its fallback:
     * `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` from Android 11, which takes no negative button, and
     * `setDeviceCredentialAllowed` on 10. Needs USE_BIOMETRIC in the manifest, without which
     * Android refuses it outright. A refusal or an error comes back as [AuthResult.Failed].
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    fun prompt(context: Context, title: String, subtitle: String, cancel: CancellationSignal, onResult: (AuthResult) -> Unit) {
        try {
            val builder = BiometricPrompt.Builder(context).setTitle(title).setSubtitle(subtitle)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            builder.build().authenticate(
                cancel,
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(AuthResult.Confirmed)
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val outcome = resultOf(errorCode, errString)
                        if (outcome is AuthResult.Failed) Diagnostics.event(LOCK_TAG, "the phone's prompt failed: $errorCode ${outcome.reason}")
                        onResult(outcome)
                    }
                },
            )
        } catch (e: Exception) {
            Diagnostics.event(LOCK_TAG, "the phone's prompt could not open: ${e.javaClass.simpleName}: ${e.message}")
            onResult(AuthResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName, e))
        }
    }

    private const val LOCK_TAG = "RexPocketLock"
}

/**
 * The phone's own prompt. From Android 10 that is the biometric prompt ([AppLockGate.prompt]),
 * which falls back to the screen lock's PIN, pattern or password; before it, the screen lock's own
 * confirm screen.
 */
@Composable
internal fun rememberDeviceAuthenticator(): Authenticate {
    val context = LocalContext.current
    val title = stringResource(R.string.security_prompt_title)
    val subtitle = stringResource(R.string.security_prompt_body)
    var pending by remember { mutableStateOf<((AuthResult) -> Unit)?>(null) }
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending?.invoke(if (result.resultCode == Activity.RESULT_OK) AuthResult.Confirmed else AuthResult.Cancelled)
        pending = null
    }
    return remember(context, title, subtitle) {
        Authenticate { onResult ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                AppLockGate.prompt(context, title, subtitle, CancellationSignal(), onResult)
            } else {
                @Suppress("DEPRECATION")
                val intent = context.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, subtitle)
                if (intent == null) {
                    onResult(AuthResult.Failed(context.getString(R.string.security_lock_no_screen_lock)))
                } else {
                    pending = onResult
                    try {
                        confirm.launch(intent)
                    } catch (e: Exception) {
                        pending = null
                        onResult(AuthResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName, e))
                    }
                }
            }
        }
    }
}

/**
 * The app, or the lock screen in its place while [locked]. The app is left out of the composition
 * rather than covered, so no sheet or dialog of it floats over the lock; its saved state comes back
 * with it. The prompt opens by itself once per lock, as soon as the app is in front (Android
 * refuses a prompt from an app that is not, which a cold start's first frame may not yet be), and
 * Unlock opens it again. A prompt that could not ask says why on the lock screen.
 */
@Composable
internal fun LockGate(
    locked: Boolean,
    authenticate: Authenticate,
    onAsking: () -> Unit,
    onResult: (AuthResult) -> Unit,
    content: @Composable () -> Unit,
) {
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    if (!locked) {
        saved.SaveableStateProvider("app") { content() }
        return
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val ask = {
        problem = null
        onAsking()
        authenticate.ask { result ->
            if (result is AuthResult.Failed) problem = result.reason
            onResult(result)
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        if (!asked) {
            lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            asked = true
            ask()
        }
    }
    LockScreen(onUnlock = ask, problem = problem)
}

@Composable
internal fun LockScreen(onUnlock: () -> Unit, problem: String? = null) {
    val unlock = stringResource(R.string.security_unlock_description)
    Column(
        Modifier.fillMaxSize().background(Colors.bg).padding(horizontal = 32.dp).testTag(LOCK_SCREEN_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Glyph(R.drawable.ic_lock, Colors.muted, size = 44.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.security_locked_title),
            color = Colors.ink,
            fontSize = TextStyles.title,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.security_locked_body),
            color = Colors.muted,
            fontSize = TextStyles.label,
            lineHeight = labelLeading,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        if (problem != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.security_prompt_failed, problem),
                color = Colors.danger,
                fontSize = TextStyles.label,
                lineHeight = labelLeading,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(16.dp))
        TextButton(
            onClick = onUnlock,
            modifier = Modifier.heightIn(min = 48.dp).testTag(UNLOCK_TAG).semantics { contentDescription = unlock },
        ) { Text(stringResource(R.string.security_unlock), color = Colors.accent, fontWeight = FontWeight.SemiBold) }
    }
}

/** "At once", "After 1 minute", ... for a lock wait in seconds. */
@Composable
internal fun lockAfterText(seconds: Int): String = when {
    seconds <= 0 -> stringResource(R.string.security_lock_after_now)
    seconds < 3600 -> stringResource(R.string.security_lock_after_minutes, seconds / 60)
    else -> stringResource(R.string.security_lock_after_hours, seconds / 3600)
}

/**
 * Settings › Security: Lock with the phone's screen lock, how long away before it asks, and Block
 * screenshots. Turning the lock on asks the phone first, so a person who cannot answer it is never
 * locked out. A tap that cannot go ahead says why, on the row and out loud (a toast TalkBack reads):
 * no screen lock, a no, or the phone failing to ask. [problem] is asked at each tap, so a screen
 * lock set meanwhile counts.
 */
@Composable
internal fun SecuritySheet(
    prefs: SecurityPrefs,
    onPrefs: (SecurityPrefs) -> Unit,
    onDismiss: () -> Unit,
    authenticate: Authenticate,
    problem: () -> String?,
    /** The back arrow before the title, when the page was opened from Settings: back to it. */
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var pickWait by remember { mutableStateOf(false) }
    // What the last tap could not do, said on the row until the next one.
    var said by remember { mutableStateOf<String?>(null) }
    val firstProblem = remember { problem() }
    val on = stringResource(R.string.alerts_on)
    val off = stringResource(R.string.alerts_off)
    fun say(text: String) {
        said = text
        toast(context, text)
    }
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.security_title), onBack = onBack) {
        Group {
            SheetItem(
                label = stringResource(R.string.security_lock),
                icon = R.drawable.ic_lock,
                supporting = said ?: firstProblem?.takeIf { !prefs.lock } ?: stringResource(R.string.security_lock_body),
                detail = if (prefs.lock) on else off,
                checked = prefs.lock,
                keepOpen = true,
                modifier = Modifier.testTag(SECURITY_LOCK_TAG).guideTarget(GuideSpot.AppLock),
            ) {
                if (prefs.lock) {
                    said = null
                    onPrefs(prefs.copy(lock = false))
                    return@SheetItem
                }
                val why = problem()
                if (why != null) return@SheetItem say(why)
                // The person shows they can answer the prompt before it guards the app.
                authenticate.ask { result ->
                    when (result) {
                        AuthResult.Confirmed -> {
                            said = null
                            onPrefs(prefs.copy(lock = true))
                        }
                        AuthResult.Cancelled -> say(context.getString(R.string.security_lock_not_confirmed))
                        is AuthResult.Failed -> say(context.getString(R.string.security_lock_failed, result.reason))
                    }
                }
            }
            SheetItem(
                label = stringResource(R.string.security_lock_after),
                icon = R.drawable.ic_timer,
                detail = lockAfterText(prefs.lockAfterSeconds),
                keepOpen = true,
                opens = true,
                enabled = prefs.lock,
            ) { pickWait = true }
        }
        Group {
            SheetItem(
                label = stringResource(R.string.security_block_screenshots),
                icon = R.drawable.ic_block,
                supporting = stringResource(R.string.security_block_screenshots_body),
                detail = if (prefs.blockScreenshots) on else off,
                checked = prefs.blockScreenshots,
                keepOpen = true,
                modifier = Modifier.testTag(SECURITY_SCREENSHOTS_TAG).guideTarget(GuideSpot.BlockScreenshots),
            ) { onPrefs(prefs.copy(blockScreenshots = !prefs.blockScreenshots)) }
        }
        Text(
            stringResource(R.string.security_note),
            color = Colors.muted,
            fontSize = TextStyles.label,
            lineHeight = labelLeading,
            modifier = Modifier.padding(16.dp),
        )
    }
    if (pickWait) {
        ChoicePicker(
            title = stringResource(R.string.security_lock_after),
            options = SecurityPrefs.LOCK_AFTER,
            current = prefs.lockAfterSeconds,
            choice = ::lockAfterChoice,
            onDismiss = { pickWait = false },
            onPick = {
                pickWait = false
                onPrefs(prefs.copy(lockAfterSeconds = it))
            },
        )
    }
}

private fun lockAfterChoice(seconds: Int): Choice = when (seconds) {
    0 -> Choice(R.string.security_wait_now, R.drawable.ic_lock, R.string.security_wait_now_hint)
    60 -> Choice(R.string.security_wait_1m, R.drawable.ic_timer, R.string.security_wait_hint)
    300 -> Choice(R.string.security_wait_5m, R.drawable.ic_timer, R.string.security_wait_hint)
    900 -> Choice(R.string.security_wait_15m, R.drawable.ic_timer, R.string.security_wait_hint)
    else -> Choice(R.string.security_wait_1h, R.drawable.ic_timer, R.string.security_wait_hint)
}

/**
 * Settings › Security's row detail: what guards the app, each part named, so a screenshot block is
 * not hidden behind the lock: "Locked with the phone's lock · Screenshots blocked".
 */
internal fun securityDetail(prefs: SecurityPrefs): List<Int> = listOfNotNull(
    R.string.security_detail_locked.takeIf { prefs.lock },
    R.string.security_detail_screenshots.takeIf { prefs.blockScreenshots },
).ifEmpty { listOf(R.string.security_detail_off) }
