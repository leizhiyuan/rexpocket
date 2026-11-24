package me.leaver.rexpocket.net.addon

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.netbird.api.NetbirdAddon
import me.leaver.rexpocket.ui.Colors
import me.leaver.rexpocket.ui.TextStyles
import me.leaver.rexpocket.ui.labelLeading
import me.leaver.rexpocket.ui.openLink

/**
 * Where the add-on's APK is published: the releases the app's updater reads, and each CI run's
 * rexpocket-debug-apks artifact, which carries it beside rexpocket.apk.
 */
internal const val NETBIRD_ADDON_URL = "https://github.com/leizhiyuan/rexpocket/releases"

/**
 * What the Tailnet dialog says about [backend] in this build: for the add-on, whether it is
 * installed, and where to get it when it is not.
 */
@Composable
internal fun TailnetBackendNote(backend: TailnetBackend) {
    if (backend != TailnetBackend.NetbirdAddon) return
    val context = LocalContext.current
    val installed = remember(backend) { ServiceAddonLink(context.applicationContext).installed() }
    if (installed) {
        Text(stringResource(R.string.netbird_addon_installed), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
    } else {
        Text(
            stringResource(R.string.netbird_addon_missing, NetbirdAddon.APK_NAME),
            color = Colors.amber,
            fontSize = TextStyles.label,
            lineHeight = labelLeading,
        )
        TextButton(onClick = { openLink(context, NETBIRD_ADDON_URL) }) {
            Text(stringResource(R.string.netbird_addon_get), color = Colors.accent)
        }
    }
}
