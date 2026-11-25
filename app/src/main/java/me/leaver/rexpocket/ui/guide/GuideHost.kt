package me.leaver.rexpocket.ui.guide

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.PaletteEntry
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.ui.PaletteRun
import me.leaver.rexpocket.ui.RexViewModel
import me.leaver.rexpocket.ui.Route
import me.leaver.rexpocket.ui.toast

// Going where an entry's control is: the route, what the place needs first, and the tour. The
// screens open their own places (GuideOpens); this is what the app as a whole does. See
// docs/design/help-guide.md.

/**
 * Help's sheet and everything Show me and the tour do, for the whole app: in RexApp, over both
 * screens. [homeShown] is whether the home is on screen (beside a pane, or alone).
 */
@Composable
internal fun GuideHost(guide: Guide, model: RexViewModel, homeShown: Boolean) {
    val context = LocalContext.current
    val hosts by model.hosts.collectAsState()
    val route by model.route.collectAsState()
    val pocket by model.pocket.collectAsState()
    val home = rememberUpdatedState(homeShown)
    LaunchedEffect(guide, model) {
        snapshotFlow { guide.pending }.filterNotNull().collectLatest { go ->
            guide.pending = null
            when (go) {
                is GuideGo.Entry -> goTo(guide, model, home, context, go.entry, go.hostId)
                is GuideGo.OpenTab -> {
                    if (openTab(model, go.hostId)) {
                        goTo(guide, model, home, context, go.entry, null)
                    } else {
                        val name = model.hosts.value.firstOrNull { it.id == go.hostId }?.label.orEmpty()
                        toast(context, context.getString(R.string.guide_tab_failed, name))
                        guide.openHelp(go.entry.id, HelpNote.NeedsTab)
                    }
                }
                GuideGo.Tour -> {
                    GuidePrefs(context).tourDone()
                    tour(guide, model, home, context)
                }
            }
        }
    }
    // Once, by itself, on a new install, the first time the app opens with a host: see GuidePrefs.
    val due = remember { GuidePrefs(context).tourDue(hostsNow = model.hosts.value.isNotEmpty()) }
    LaunchedEffect(due) {
        if (!due) return@LaunchedEffect
        delay(AUTO_TOUR_DELAY_MS)
        val quiet = withTimeoutOrNull(AUTO_TOUR_WAIT_MS) {
            snapshotFlow {
                home.value && route == Route.Home && guide.layers <= 1 && guide.help == null &&
                    guide.spotlight == null && guide.pending == null
            }.first { it }
        }
        if (quiet != null) guide.startTour()
    }
    guide.help?.let { view ->
        GuideSheet(
            guide = guide,
            view = view,
            hosts = hosts,
            terminalReady = route == Route.Term && pocketReady(pocket),
        )
    }
}

/** A pane on screen to point at: the terminal live on a tab, not a closed pane's note. */
private fun pocketReady(state: me.leaver.rexpocket.core.PocketState): Boolean =
    state.phase == Phase.Live && state.closed == null && state.view?.focusedBlock() != null

private fun terminalReady(model: RexViewModel): Boolean = model.route.value == Route.Term && pocketReady(model.pocket.value)

/** Waits for [condition] on the home being shown, or gives up after [ms]. */
private suspend fun awaitHome(home: State<Boolean>, ms: Long): Boolean =
    withTimeoutOrNull(ms) { snapshotFlow { home.value }.first { it } } != null

/**
 * Goes to [entry]'s place and points at its control. What the place needs and is not there - a tab
 * on screen, a host, or which host - brings Help back on the entry, saying so; a control that does
 * not show up in time does too, rather than a ring round nothing.
 */
private suspend fun goTo(guide: Guide, model: RexViewModel, home: State<Boolean>, context: Context, entry: GuideEntry, hostId: String?) {
    var host = hostId
    if (entry.place.terminal) {
        if (!terminalReady(model)) return guide.openHelp(entry.id, HelpNote.NeedsTab)
    } else if (!home.value) {
        model.showHome()
        if (!awaitHome(home, HOME_WAIT_MS)) return guide.openHelp(entry.id, HelpNote.CouldNotShow)
    }
    if (entry.place.needsHost) {
        val hosts = model.hosts.value
        if (hosts.isEmpty()) return guide.openHelp(entry.id, HelpNote.NeedsHost)
        host = host?.takeIf { id -> hosts.any { it.id == id } } ?: hosts.singleOrNull()?.id
        if (host == null) return guide.openHelp(entry.id, HelpNote.PickHost)
    }
    val spotlight = Spotlight(entry.spot, context.getString(entry.title), context.getString(entry.hint))
    guide.point(spotlight)
    guide.ask(entry.place, host)
    val found = withTimeoutOrNull(FIND_MS) { snapshotFlow { guide.shown }.first { it == entry.spot } }
    if (found == null && guide.spotlight === spotlight) {
        guide.dismiss()
        guide.request = null
        guide.openHelp(entry.id, HelpNote.CouldNotShow)
    }
}

/**
 * Opens a tab on [hostId] for a terminal entry: its first session (connecting first), or a new one
 * on a host that has none. True once a pane is on screen.
 */
private suspend fun openTab(model: RexViewModel, hostId: String): Boolean {
    val host = model.hosts.value.firstOrNull { it.id == hostId } ?: return false
    val session = model.lives.value[hostId]?.state?.sessions?.firstOrNull()?.id
        ?: model.cachedSessions(hostId).firstOrNull()?.id
    if (session != null) model.openSessionOn(host, session) else model.createSessionOn(host)
    val ready = withTimeoutOrNull(TAB_WAIT_MS) {
        combine(model.route, model.pocket) { r, p -> r == Route.Term && pocketReady(p) }.first { it }
    }
    return ready != null
}

/** The quick tour: each step in turn on the home, skipping one whose control is not there. */
private suspend fun tour(guide: Guide, model: RexViewModel, home: State<Boolean>, context: Context) {
    if (!home.value) {
        model.showHome()
        if (!awaitHome(home, HOME_WAIT_MS)) return
    }
    guide.ask(GuidePlace.Home, null)
    for ((index, step) in TOUR_STEPS.withIndex()) {
        val spotlight = Spotlight(step.spot, context.getString(step.title), context.getString(step.hint), index, TOUR_STEPS.size)
        val asked = guide.nextAsked
        guide.point(spotlight)
        val found = withTimeoutOrNull(TOUR_FIND_MS) { snapshotFlow { guide.shown }.first { it == step.spot } }
        if (guide.spotlight !== spotlight) return
        if (found == null) continue
        snapshotFlow { guide.nextAsked != asked || guide.spotlight !== spotlight }.first { it }
        if (guide.spotlight !== spotlight) return
    }
    guide.dismiss()
}

/** The palette's rows for Help: Help itself, and one "Help: …" row per entry, which goes there at once. */
@Composable
internal fun guidePaletteEntries(guide: Guide?): List<PaletteEntry<PaletteRun>> {
    if (guide == null) return emptyList()
    val group = stringResource(R.string.guide_palette_group)
    val words = guideWords()
    val open = PaletteEntry(stringResource(R.string.guide_menu), group, null, PaletteRun { guide.openHelp() }, keywords = stringResource(R.string.guide_menu_keys))
    return listOf(open) + GUIDE_ENTRIES.map { entry ->
        PaletteEntry(
            stringResource(R.string.guide_palette_row, stringResource(entry.title)),
            group,
            stringResource(entry.hint),
            PaletteRun { guide.show(entry) },
            keywords = words.getValue(entry.id),
        )
    }
}

/**
 * The tour runs by itself once, on a new install only: the first start records whether the app had
 * hosts then. One that had none is new; once it starts with a host, the tour is due until it ran.
 * An install that already had hosts when this came - everyone using the app before - never gets it
 * by itself. Help's Take the quick tour runs it any time. The phone's own hint, like the pinch hint:
 * not in the settings backup.
 */
internal class GuidePrefs(context: Context) {
    private val prefs = context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)

    fun tourDue(hostsNow: Boolean): Boolean {
        val (due, record) = tourDue(prefs.getString(KEY_INSTALL, null), hostsNow, prefs.getBoolean(KEY_TOUR, false))
        if (record != null) prefs.edit().putString(KEY_INSTALL, record).apply()
        return due
    }

    fun tourDone() {
        prefs.edit().putBoolean(KEY_TOUR, true).apply()
    }

    companion object {
        /**
         * Whether the tour is due at a start, from what the first start recorded ([install]), whether
         * there are hosts now and whether it ran; and what to record, the first time.
         */
        fun tourDue(install: String?, hostsNow: Boolean, done: Boolean): Pair<Boolean, String?> = when (install) {
            null -> false to (if (hostsNow) INSTALL_OLD else INSTALL_NEW)
            INSTALL_NEW -> (hostsNow && !done) to null
            else -> false to null
        }
    }
}

internal const val GUIDE_PREFS = "guide"
private const val KEY_INSTALL = "install"
private const val KEY_TOUR = "tour"
internal const val INSTALL_NEW = "new"
internal const val INSTALL_OLD = "old"

/** How long a control has to show up once its place is open. */
private const val FIND_MS = 4_000L
private const val TOUR_FIND_MS = 1_500L
private const val HOME_WAIT_MS = 2_000L
private const val TAB_WAIT_MS = 20_000L
private const val AUTO_TOUR_DELAY_MS = 1_200L
private const val AUTO_TOUR_WAIT_MS = 60_000L
