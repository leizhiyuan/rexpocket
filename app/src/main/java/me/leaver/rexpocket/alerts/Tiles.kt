package me.leaver.rexpocket.alerts

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.leaver.rexpocket.MainActivity
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.Connectable
import me.leaver.rexpocket.core.ConnectableKind
import me.leaver.rexpocket.core.LinkState
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.ui.Connections

// Quick Settings tiles, the same for a host and a tailnet: a tap connects or disconnects, the
// subtitle says the state, a long press opens that one's page. Android declares tile services in
// the manifest, so the app cannot add one per host; it has a pool of three, each bound to a host or
// a tailnet the person picks (the first tap of an unbound tile, or Add a tile on a page). See
// docs/design/tailnet-control.md, "Tiles".

/** Which host or tailnet each of the pool's tiles is bound to, by slot (1 to [SLOTS]). */
class TileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rexpocket", Context.MODE_PRIVATE)

    fun of(slot: Int): Connectable? = Connectable.parse(prefs.getString(key(slot), null))

    fun bind(slot: Int, item: Connectable?) {
        prefs.edit().apply { if (item == null) remove(key(slot)) else putString(key(slot), item.key) }.commit()
        changes.value++
    }

    /** Every slot's item, slot 1 first; null for a free one. */
    fun all(): List<Connectable?> = (1..SLOTS).map { of(it) }

    /** The slot [item] is on, if any. */
    fun slotOf(item: Connectable): Int? = (1..SLOTS).firstOrNull { of(it) == item }

    /** The slot for [item]: the one it is on, or the first free one; null when all three are taken. */
    fun slotFor(item: Connectable): Int? = slotOf(item) ?: (1..SLOTS).firstOrNull { of(it) == null }

    private fun key(slot: Int) = "tile.$slot"

    companion object {
        const val SLOTS = 3

        /** Counts every change to a binding, so the pages that show them read them again. */
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

        /**
         * Puts [item] on [slot], or frees it when [item] is null. An item is on one tile at most:
         * one that was on another tile leaves it. Every tile that changed is told to redraw.
         */
        fun assign(context: Context, slot: Int, item: Connectable?) {
            val store = TileStore(context)
            val was = item?.let { store.slotOf(it) }?.takeIf { it != slot }
            if (was != null) store.bind(was, null)
            store.bind(slot, item)
            Diagnostics.event(TAG, "tile $slot: ${item?.kind ?: "cleared"}${if (was != null) " (moved from $was)" else ""}")
            refresh(context, slot)
            if (was != null) refresh(context, was)
        }

        /** Frees whichever tile shows [item]. */
        fun remove(context: Context, item: Connectable) {
            TileStore(context).slotOf(item)?.let { assign(context, it, null) }
        }

        /** The picker for [slot], in the app's own activity: Change… on a page and in Settings. */
        fun pickerIntent(context: Context, slot: Int): Intent =
            Intent(context, TilePickerActivity::class.java).putExtra(TilePickerActivity.EXTRA_SLOT, slot)

        fun component(context: Context, slot: Int): ComponentName = ComponentName(context, serviceOf(slot))

        private fun serviceOf(slot: Int): Class<out ConnectableTile> = when (slot) {
            1 -> RexTile1::class.java
            2 -> RexTile2::class.java
            else -> RexTile3::class.java
        }

        /** The slot of a tile's component, as a long press hands it to the page it opens. */
        fun slotOf(component: ComponentName?): Int? =
            (1..SLOTS).firstOrNull { component?.className == serviceOf(it).name }

        /** Tells Android to ask [slot]'s tile for its look again. */
        fun refresh(context: Context, slot: Int) {
            runCatching { TileService.requestListeningState(context, component(context, slot)) }
        }

        fun refreshAll(context: Context) = (1..SLOTS).forEach { refresh(context, it) }

        /**
         * Binds a free tile to [item] and, on Android 13 and later, asks to add it to Quick
         * Settings. Gives the slot, or null when all three are bound to others.
         */
        @SuppressLint("WrongConstant")
        fun add(context: Context, item: Connectable, label: String): Int? {
            val store = TileStore(context)
            val slot = store.slotFor(item) ?: return null
            assign(context, slot, item)
            return requestAdd(context, slot, item, label)
        }

        /** On Android 13 and later, asks Android to put [slot]'s tile in Quick Settings. */
        @SuppressLint("WrongConstant")
        fun requestAdd(context: Context, slot: Int, item: Connectable, label: String): Int {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                        component(context, slot),
                        label,
                        Icon.createWithResource(context, if (item.kind == ConnectableKind.Tailnet) R.drawable.ic_tailnet else R.drawable.ic_host),
                        context.mainExecutor,
                    ) { result -> Diagnostics.event(TAG, "tile $slot add: $result") }
                }
            }
            return slot
        }

        /** The intent of a long press on a tile, or of a tile whose item is gone: that one's page, or the picker. */
        fun pageIntent(context: Context, item: Connectable?, slot: Int): Intent =
            if (item == null) {
                Intent(context, TilePickerActivity::class.java).putExtra(TilePickerActivity.EXTRA_SLOT, slot)
            } else {
                Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_PAGE).putExtra(EXTRA_ITEM, item.key)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens a host's or a tailnet's page in the app: the intent's action, with [EXTRA_ITEM]. */
        const val ACTION_OPEN_PAGE = "me.leaver.rexpocket.OPEN_PAGE"

        /** Asks, in the app, whether to disconnect a tailnet that hosts use: with [EXTRA_ITEM]. */
        const val ACTION_CONFIRM_OFF = "me.leaver.rexpocket.CONFIRM_OFF"
        const val EXTRA_ITEM = "item"
        private const val TAG = "RexPocketTile"
    }
}

/**
 * One tile of the pool. While Quick Settings is open it follows its item's state; a tap connects
 * it when it is off and disconnects it otherwise (a tailnet that hosts use asks first, in the
 * app); an unbound tile, or one whose item is gone, opens the picker.
 */
abstract class ConnectableTile(private val slot: Int) : TileService() {
    private var scope: CoroutineScope? = null
    private var follow: Job? = null

    private val connections: Connections get() = Connections.of(application as Application)

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        follow = s.launch { connections.changes.collect { show() } }
        show()
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val item = TileStore(this).of(slot)?.takeIf { connections.nameOf(it) != null }
        if (item == null) {
            open(TileStore.pageIntent(this, null, slot))
            return
        }
        val state = connections.stateOf(item)
        Diagnostics.event(TAG, "tile $slot: ${item.kind} ${if (state == LinkState.Off) "connect" else "disconnect"}")
        when {
            state == LinkState.Off -> {
                connections.connectItem(item)
                // The connection lives as long as the process: background mode's watch holds it;
                // without it, the app is opened, which is what holds it then.
                if (Alerts.enabled.value) Alerts.watch(this) else open(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            item.kind == ConnectableKind.Tailnet && connections.hostsOn(item.id).isNotEmpty() ->
                open(Intent(this, MainActivity::class.java).setAction(TileStore.ACTION_CONFIRM_OFF).putExtra(TileStore.EXTRA_ITEM, item.key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> connections.disconnectItem(item)
        }
        show()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, slot, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun show() {
        val tile = qsTile ?: return
        val bound = TileStore(this).of(slot)
        val name = bound?.let { connections.nameOf(it) }
        val state = if (bound != null && name != null) connections.stateOf(bound) else null
        tile.label = name ?: getString(R.string.tile_slot_label, slot)
        tile.icon = Icon.createWithResource(this, if (bound?.kind == ConnectableKind.Tailnet) R.drawable.ic_tailnet else R.drawable.ic_host)
        tile.state = when (state) {
            null -> Tile.STATE_INACTIVE
            LinkState.Off -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        val subtitle = when {
            bound == null -> getString(R.string.tile_pick)
            name == null -> getString(R.string.tile_gone)
            else -> getString(stateWord(state ?: LinkState.Off))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.contentDescription = "${tile.label}, $subtitle"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = subtitle
        tile.updateTile()
    }

    private companion object {
        const val TAG = "RexPocketTile"
    }
}

/** The state words, as strings, for the tile and the watch notification. */
internal fun stateWord(state: LinkState): Int = when (state) {
    LinkState.Off -> R.string.conn_state_off
    LinkState.Connecting -> R.string.conn_state_connecting
    LinkState.Connected -> R.string.conn_state_connected
    LinkState.NeedsSignIn -> R.string.conn_state_sign_in
    LinkState.Failed -> R.string.conn_state_failed
}

class RexTile1 : ConnectableTile(1)
class RexTile2 : ConnectableTile(2)
class RexTile3 : ConnectableTile(3)
