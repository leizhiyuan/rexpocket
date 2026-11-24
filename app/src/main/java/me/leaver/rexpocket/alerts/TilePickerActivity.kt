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
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ConnectableKind
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.ui.Colors
import me.leaver.rexpocket.ui.Connections
import me.leaver.rexpocket.ui.Glyph
import me.leaver.rexpocket.ui.RexTheme
import me.leaver.rexpocket.ui.TextStyles

/**
 * What one of the pool's tiles connects: the joined tailnet or a saved host, picked from a list
 * over whatever is on screen. A pick binds the tile and refreshes it; the next tap connects.
 */
class TilePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slot = intent.getIntExtra(EXTRA_SLOT, 1).coerceIn(1, TileStore.SLOTS)
        val connections = Connections.of(application as Application)
        val items = connections.connectables().mapNotNull { item -> connections.nameOf(item)?.let { item to it } }
        val appearance = HostStore(this).appearance()
        setContent {
            RexTheme(appearance.app, appearance.terminal, appearance.text) {
                AlertDialog(
                    onDismissRequest = ::finish,
                    containerColor = Colors.surface,
                    title = { Text(stringResource(R.string.tile_picker_title), color = Colors.ink) },
                    text = {
                        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                            for ((item, name) in items) {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) {
                                        TileStore(this@TilePickerActivity).bind(slot, item)
                                        TileStore.refresh(this@TilePickerActivity, slot)
                                        finish()
                                    }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Glyph(if (item.kind == ConnectableKind.Tailnet) R.drawable.ic_tailnet else R.drawable.ic_host, Colors.muted, size = 24.dp)
                                    Spacer(Modifier.width(16.dp))
                                    Text(name, color = Colors.ink, fontSize = TextStyles.body)
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = ::finish) { Text(stringResource(R.string.action_cancel), color = Colors.muted) } },
                )
            }
        }
    }

    companion object {
        const val EXTRA_SLOT = "slot"
    }
}
