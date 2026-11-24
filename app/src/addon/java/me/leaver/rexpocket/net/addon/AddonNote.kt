package me.leaver.rexpocket.net.addon

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import me.leaver.rexpocket.BuildConfig
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.netbird.api.NetbirdAddon
import me.leaver.rexpocket.ui.Colors
import me.leaver.rexpocket.ui.Group
import me.leaver.rexpocket.ui.SheetItem
import me.leaver.rexpocket.ui.SheetScope
import me.leaver.rexpocket.ui.TextStyles
import me.leaver.rexpocket.ui.labelLeading
import me.leaver.rexpocket.ui.openLink

/**
 * Where the add-on's APK is published: the releases the app's updater reads, and each CI run's
 * rexpocket-debug-apks artifact, which carries it beside rexpocket.apk.
 */
internal const val NETBIRD_ADDON_URL = "https://github.com/leizhiyuan/rexpocket/releases"

/** The tag of the add-on's Update row and button, for the screen tests. */
internal const val ADDON_UPDATE_TAG = "netbird-addon-update"

/** The add-on's package as it is now, read again each time the screen comes back (it may have been installed). */
@Composable
private fun rememberAddonPackage(): AddonPackage {
    val context = LocalContext.current
    var pkg by remember { mutableStateOf(inspectAddon(context.applicationContext)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { pkg = inspectAddon(context.applicationContext) }
    return pkg
}

/** What an update of the add-on is doing, in words; null while it is doing nothing. */
@Composable
private fun updateProgress(): String? = when (val s = AddonUpdates.state.collectAsState().value) {
    AddonUpdates.State.Idle -> null
    AddonUpdates.State.Checking -> stringResource(R.string.netbird_addon_update_checking)
    is AddonUpdates.State.Downloading -> stringResource(R.string.netbird_addon_update_downloading, s.percent)
    AddonUpdates.State.Installing -> stringResource(R.string.netbird_addon_update_installing)
    AddonUpdates.State.NeedsPermission -> stringResource(R.string.netbird_addon_update_permission)
    AddonUpdates.State.NotPublished -> stringResource(R.string.netbird_addon_update_none)
    is AddonUpdates.State.Failed -> stringResource(R.string.netbird_addon_update_failed, s.detail)
}

/** Why the add-on wants updating: it is older than this app, and what that costs. */
@Composable
private fun outdatedText(pkg: AddonPackage): String =
    stringResource(R.string.netbird_addon_outdated, pkg.versionName.ifBlank { "?" }, BuildConfig.VERSION_NAME) +
        if (pkg.revision < NetbirdAddon.REVISION_ACCOUNTS) " " + stringResource(R.string.netbird_addon_outdated_accounts) else ""

/**
 * What the Tailnet dialog says about [backend] in this build: for the add-on, whether it is
 * installed and ours, where to get it when it is not, and an Update when it is older than this app.
 */
@Composable
internal fun TailnetBackendNote(backend: TailnetBackend) {
    if (backend != TailnetBackend.NetbirdAddon) return
    val context = LocalContext.current
    val pkg = rememberAddonPackage()
    when {
        !pkg.installed -> {
            Text(
                stringResource(R.string.netbird_addon_missing, NetbirdAddon.APK_NAME),
                color = Colors.amber,
                fontSize = TextStyles.label,
                lineHeight = labelLeading,
            )
            TextButton(onClick = { openLink(context, NETBIRD_ADDON_URL) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.netbird_addon_get), color = Colors.accent)
            }
        }
        !pkg.trusted -> {
            Text(stringResource(R.string.netbird_addon_not_allowed), color = Colors.danger, fontSize = TextStyles.label, lineHeight = labelLeading)
            // The way out: uninstall this one from its App info, then get the matching one.
            TextButton(onClick = { openAddonInfo(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.netbird_addon_app_info), color = Colors.accent)
            }
            TextButton(onClick = { openLink(context, NETBIRD_ADDON_URL) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.netbird_addon_get), color = Colors.accent)
            }
        }
        pkg.olderThan(BuildConfig.VERSION_NAME) -> {
            Text(outdatedText(pkg), color = Colors.amber, fontSize = TextStyles.label, lineHeight = labelLeading)
            updateProgress()?.let { Text(it, color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading) }
            TextButton(
                onClick = { AddonUpdates.update(context) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(ADDON_UPDATE_TAG),
            ) {
                Text(stringResource(R.string.netbird_addon_update), color = Colors.accent)
            }
        }
        else -> Text(stringResource(R.string.netbird_addon_installed), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
    }
}

/**
 * What the tailnet's page says about [backend] under its state: for an add-on that is missing,
 * not ours or older than this app, a row that says so and gets or updates it. Nothing otherwise.
 */
@Composable
internal fun TailnetBackendRow(scope: SheetScope, backend: TailnetBackend) = with(scope) {
    if (backend != TailnetBackend.NetbirdAddon) return@with
    val context = LocalContext.current
    val pkg = rememberAddonPackage()
    when {
        !pkg.installed -> Group {
            SheetItem(
                stringResource(R.string.netbird_addon_get),
                color = Colors.accent,
                supporting = stringResource(R.string.netbird_addon_missing, NetbirdAddon.APK_NAME),
                keepOpen = true,
            ) { openLink(context, NETBIRD_ADDON_URL) }
        }
        !pkg.trusted -> Group {
            SheetItem(stringResource(R.string.netbird_addon_not_allowed), color = Colors.danger, enabled = false) {}
            SheetItem(
                stringResource(R.string.netbird_addon_app_info),
                color = Colors.accent,
                supporting = stringResource(R.string.netbird_addon_app_info_body),
                keepOpen = true,
            ) { openAddonInfo(context) }
            SheetItem(stringResource(R.string.netbird_addon_get), color = Colors.accent, keepOpen = true) { openLink(context, NETBIRD_ADDON_URL) }
        }
        pkg.olderThan(BuildConfig.VERSION_NAME) -> Group {
            SheetItem(
                stringResource(R.string.netbird_addon_update),
                color = Colors.accent,
                supporting = updateProgress() ?: outdatedText(pkg),
                keepOpen = true,
            ) { AddonUpdates.update(context) }
        }
        else -> Unit
    }
}

/** The add-on's App info, where it is uninstalled before the matching one goes in. */
private fun openAddonInfo(context: android.content.Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", NetbirdAddon.PACKAGE, null),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
