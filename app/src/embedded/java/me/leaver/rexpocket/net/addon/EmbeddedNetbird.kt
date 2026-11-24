package me.leaver.rexpocket.net.addon

import android.content.Context
import androidx.compose.runtime.Composable
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.net.NetbirdBox
import me.leaver.rexpocket.net.TailnetEntry

/**
 * The build with Netbird embedded has no Netbird add-on: what src/addon has in the default build is
 * here as nothing. A choice of the add-on stored by the default build, which this build replaces,
 * runs the embedded node instead; it is not offered.
 */
internal fun addonEntries(context: Context, netbird: NetbirdBox): List<TailnetEntry> =
    listOf(TailnetEntry(TailnetBackend.NetbirdAddon, netbird) { false })

/** Nothing to say about a backend: the embedded build has no add-on. */
@Composable
internal fun TailnetBackendNote(@Suppress("UNUSED_PARAMETER") backend: TailnetBackend) = Unit
