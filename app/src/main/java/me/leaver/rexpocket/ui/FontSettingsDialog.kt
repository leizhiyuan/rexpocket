package me.leaver.rexpocket.ui

import android.graphics.Typeface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.data.FontCatalog
import me.leaver.rexpocket.data.SYSTEM_FONT
import me.leaver.rexpocket.data.TerminalFontState

@Composable
internal fun FontSettingsDialog(
    state: TerminalFontState,
    typeface: Typeface,
    onSelect: (String) -> Unit,
    /** The text size new tabs start at, with − / + and Reset: the font's size beside its face. */
    fontScale: Float = me.leaver.rexpocket.core.TextScale.DEFAULT,
    onFontScale: (Float) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val enabled = !state.initializing && state.downloading == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_font_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.terminal_font_hint))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Aa 0O 1l {} [] →\n\uE0B0 \uF07B \uE718 \uDB80\uDE4B",
                    fontFamily = FontFamily(typeface),
                )
                DefaultTextSize(fontScale, onFontScale, Modifier.padding(top = 8.dp))
                FontOption(
                    stringResource(R.string.terminal_font_system),
                    stringResource(R.string.terminal_font_system_detail),
                    state.selected == SYSTEM_FONT, enabled,
                ) { onSelect(SYSTEM_FONT) }
                for (font in FontCatalog.fonts) {
                    val detail = when {
                        state.downloading == font.id -> stringResource(R.string.terminal_font_downloading, (state.progress * 100).toInt())
                        font.id in state.available -> stringResource(R.string.terminal_font_saved)
                        else -> stringResource(R.string.terminal_font_download)
                    }
                    FontOption(
                        font.name,
                        detail,
                        state.selected == font.id, enabled,
                    ) { onSelect(font.id) }
                }
                if (state.initializing) Text(stringResource(R.string.terminal_font_loading))
                if (state.downloading != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }
                if (state.failed != null) Text(stringResource(R.string.terminal_font_failed), color = Colors.danger)
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
