package me.leaver.rexpocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.data.TerminalFontState

@Composable
internal fun FontSettingsDialog(state: TerminalFontState, onSelect: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val enabled = !state.initializing && !state.downloading
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_font_title)) },
        text = {
            Column {
                Text(stringResource(R.string.terminal_font_hint))
                FontOption(
                    stringResource(R.string.terminal_font_system),
                    stringResource(R.string.terminal_font_system_detail),
                    !state.selected, enabled,
                ) { onSelect(false) }
                FontOption(
                    "JetBrains Mono",
                    stringResource(if (state.available) R.string.terminal_font_saved else R.string.terminal_font_download),
                    state.selected, enabled,
                ) { onSelect(true) }
                if (state.initializing) Text(stringResource(R.string.terminal_font_loading))
                if (state.downloading) {
                    Text(stringResource(R.string.terminal_font_downloading, (state.progress * 100).toInt()))
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }
                if (state.failed) Text(stringResource(R.string.terminal_font_failed), color = Colors.danger)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.terminal_font_credit), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}

@Composable
private fun FontOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title)
            Text(detail, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
    }
}
