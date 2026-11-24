package me.leaver.rexpocket.ui

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.SecurityPrefs

// Settings › Security: the app lock, the screen that stands in for the app while it is locked, and
// the screenshot block. See docs/design/app-lock.md.

internal const val LOCK_SCREEN_TAG = "app-lock-screen"
internal const val UNLOCK_TAG = "app-unlock"
internal const val SECURITY_LOCK_TAG = "security-lock"
internal const val SECURITY_SCREENSHOTS_TAG = "security-screenshots"

/** Asks the phone who is there: its fingerprint, face or screen lock. [onResult] says whether it said yes. */
internal fun interface Authenticate {
    fun ask(onResult: (Boolean) -> Unit)
}

internal object AppLockGate {
    /** Whether the phone has a screen lock: without one there is nothing for the app lock to ask. */
    fun deviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
}

/**
 * The phone's own prompt. From Android 10 that is the biometric prompt, which falls back to the
 * screen lock's PIN, pattern or password; before it, the screen lock's own confirm screen.
 */
@Composable
internal fun rememberDeviceAuthenticator(): Authenticate {
    val context = LocalContext.current
    val title = stringResource(R.string.security_prompt_title)
    val subtitle = stringResource(R.string.security_prompt_body)
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending?.invoke(result.resultCode == Activity.RESULT_OK)
        pending = null
    }
    return remember(context, title, subtitle) {
        Authenticate { onResult ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val builder = BiometricPrompt.Builder(context).setTitle(title).setSubtitle(subtitle)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    builder.setDeviceCredentialAllowed(true)
                }
                val prompt = runCatching { builder.build() }.getOrNull() ?: return@Authenticate onResult(false)
                runCatching {
                    prompt.authenticate(
                        CancellationSignal(),
                        context.mainExecutor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
                        },
                    )
                }.onFailure { onResult(false) }
            } else {
                @Suppress("DEPRECATION")
                val intent = context.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, subtitle)
                if (intent == null) {
                    onResult(false)
                } else {
                    pending = onResult
                    runCatching { confirm.launch(intent) }.onFailure {
                        pending = null
                        onResult(false)
                    }
                }
            }
        }
    }
}

/**
 * The app, or the lock screen in its place while [locked]. The app is left out of the composition
 * rather than covered, so no sheet or dialog of it floats over the lock; its saved state comes back
 * with it. The prompt opens by itself once per lock, and Unlock opens it again.
 */
@Composable
internal fun LockGate(
    locked: Boolean,
    authenticate: Authenticate,
    onAsking: () -> Unit,
    onResult: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    if (!locked) {
        saved.SaveableStateProvider("app") { content() }
        return
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = {
        onAsking()
        authenticate.ask(onResult)
    }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            ask()
        }
    }
    LockScreen(onUnlock = ask)
}

@Composable
internal fun LockScreen(onUnlock: () -> Unit) {
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
 * locked out; a phone with no screen lock says to set one.
 */
@Composable
internal fun SecuritySheet(
    prefs: SecurityPrefs,
    onPrefs: (SecurityPrefs) -> Unit,
    onDismiss: () -> Unit,
    authenticate: Authenticate,
    deviceSecure: Boolean,
) {
    var pickWait by remember { mutableStateOf(false) }
    var notConfirmed by remember { mutableStateOf(false) }
    val on = stringResource(R.string.alerts_on)
    val off = stringResource(R.string.alerts_off)
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.security_title)) {
        Group {
            SheetItem(
                label = stringResource(R.string.security_lock),
                icon = R.drawable.ic_lock,
                supporting = when {
                    !deviceSecure && !prefs.lock -> stringResource(R.string.security_lock_no_screen_lock)
                    notConfirmed -> stringResource(R.string.security_lock_not_confirmed)
                    else -> stringResource(R.string.security_lock_body)
                },
                detail = if (prefs.lock) on else off,
                keepOpen = true,
                enabled = prefs.lock || deviceSecure,
                modifier = Modifier.testTag(SECURITY_LOCK_TAG),
            ) {
                if (prefs.lock) {
                    onPrefs(prefs.copy(lock = false))
                } else {
                    // The person shows they can answer the prompt before it guards the app.
                    authenticate.ask { ok ->
                        notConfirmed = !ok
                        if (ok) onPrefs(prefs.copy(lock = true))
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
                keepOpen = true,
                modifier = Modifier.testTag(SECURITY_SCREENSHOTS_TAG),
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
