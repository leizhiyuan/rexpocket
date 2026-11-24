package me.leaver.rexpocket.alerts

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import me.leaver.rexpocket.core.Connectable
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ConnectableKind
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.ui.Colors
import me.leaver.rexpocket.ui.Connections
import me.leaver.rexpocket.ui.Glyph
import me.leaver.rexpocket.ui.RexTheme
import me.leaver.rexpocket.ui.TextStyles

/**
 * What the pool's tiles connect: the tile asked about first ([EXTRA_SLOT]), with each tile's item
 * shown so any of them can be chosen and changed here, then the joined tailnet and the saved hosts.
 * A pick binds the chosen tile (moving the item off another tile it was on) and refreshes it; the
 * next tap connects. Clear tile frees it.
 */
class TilePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val first = intent.getIntExtra(EXTRA_SLOT, 1).coerceIn(1, TileStore.SLOTS)
        val connections = Connections.of(application as Application)
        val items = connections.connectables().mapNotNull { item -> connections.nameOf(item)?.let { item to it } }
        val appearance = HostStore(this).appearance()
        setContent {
            RexTheme(appearance.app, appearance.terminal, appearance.text) {
                TilePicker(first, items, nameOf = { connections.nameOf(it) }, onDone = ::finish)
            }
        }
    }

    companion object {
        const val EXTRA_SLOT = "slot"
    }
}

internal const val TILE_PICKER_SLOT_TAG = "tile-picker-slot-"
internal const val TILE_PICKER_CLEAR_TAG = "tile-picker-clear"

/**
 * The picker itself: the three tiles as choices, each with its item ("Tile 2 · mac"), the
 * chosen tile's item marked in the list below, and Clear tile.
 */
@Composable
internal fun TilePicker(first: Int, items: List<Pair<Connectable, String>>, nameOf: (Connectable) -> String?, onDone: () -> Unit) {
    val context = LocalContext.current
    var slot by rememberSaveable { mutableIntStateOf(first) }
    val version by TileStore.changes.collectAsState()
    val bound = remember(version) { TileStore(context).all() }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Colors.surface,
        title = { Text(stringResource(R.string.tile_picker_title_slot, slot), color = Colors.ink) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.tile_picker_tiles), color = Colors.muted, fontSize = TextStyles.label)
                for (n in 1..TileStore.SLOTS) {
                    val on = bound[n - 1]
                    val what = on?.let { nameOf(it) ?: stringResource(R.string.tile_item_gone) } ?: stringResource(R.string.tile_free)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = slot == n, role = Role.RadioButton) { slot = n }
                            .testTag(TILE_PICKER_SLOT_TAG + n),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = slot == n, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tile_slot_item, n, what), color = Colors.ink, fontSize = TextStyles.body)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tile_picker_items), color = Colors.muted, fontSize = TextStyles.label)
                for ((item, name) in items) {
                    val here = bound[slot - 1] == item
                    val elsewhere = bound.indexOf(item).takeIf { it >= 0 && it != slot - 1 }?.plus(1)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) {
                            TileStore.assign(context, slot, item)
                            onDone()
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(if (item.kind == ConnectableKind.Tailnet) R.drawable.ic_tailnet else R.drawable.ic_host, Colors.muted, size = 24.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = Colors.ink, fontSize = TextStyles.body)
                            when {
                                here -> Text(stringResource(R.string.tile_on_this), color = Colors.ok, fontSize = TextStyles.label)
                                elsewhere != null -> Text(stringResource(R.string.tile_moves_from, elsewhere), color = Colors.muted, fontSize = TextStyles.label)
                            }
                        }
                        if (here) Glyph(R.drawable.ic_check, Colors.ok, size = 24.dp)
                    }
                }
            }
        },
        confirmButton = {
            if (bound[slot - 1] != null) {
                TextButton(onClick = { TileStore.assign(context, slot, null); onDone() }, modifier = Modifier.testTag(TILE_PICKER_CLEAR_TAG)) {
                    Text(stringResource(R.string.tile_clear), color = Colors.danger)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel), color = Colors.muted) } },
    )
}
