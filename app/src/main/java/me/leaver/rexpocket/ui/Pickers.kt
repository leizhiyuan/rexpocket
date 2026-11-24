package me.leaver.rexpocket.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R

// The one way a setting with named options is changed: its row opens a list of every option, each
// with its icon and a line on what it does, the current one selected. See terminal-controls.md, 8.

/**
 * What a setting shows for one of its values: the words, the icon and a one-line description of
 * what it does. One function per setting gives it (`choice` in Controls.kt, `appearanceChoice`), so
 * a row's detail, its icon and the picker's line for the same value cannot disagree.
 */
internal data class Choice(@StringRes val label: Int, @DrawableRes val icon: Int, @StringRes val description: Int)

/** The open picker's content, for the screen tests. */
internal const val PICKER_TAG = "choice-picker"

/**
 * A row for a setting with named [options]: its detail is the current one's words, its icon the
 * current one's icon (or [icon], for a row whose icon stays), and a tap opens [ChoicePicker]. It
 * ends in a chevron, since it opens a dialog. The sheet stays open under the picker, so the row
 * shows the new value as the picker goes.
 */
@Composable
internal fun <T> SheetScope.ChoiceRow(
    label: String,
    options: List<T>,
    current: T,
    choice: (T) -> Choice,
    @DrawableRes icon: Int? = null,
    supporting: String? = null,
    onPick: (T) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val now = choice(current)
    SheetItem(
        label = label,
        icon = icon ?: now.icon,
        detail = stringResource(now.label),
        supporting = supporting,
        keepOpen = true,
        opens = true,
    ) { open = true }
    if (open) {
        ChoicePicker(
            title = label,
            options = options,
            current = current,
            choice = choice,
            onDismiss = { open = false },
            onPick = {
                open = false
                if (it != current) onPick(it)
            },
        )
    }
}

/**
 * Material's single-choice dialog: a radio row per option with its icon, its words and what it does,
 * the [current] one selected. A tap picks and closes; Cancel leaves the setting as it was.
 */
@Composable
internal fun <T> ChoicePicker(
    title: String,
    options: List<T>,
    current: T,
    choice: (T) -> Choice,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(title, color = Colors.ink) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).selectableGroup().testTag(PICKER_TAG)) {
                options.forEach { option ->
                    val c = choice(option)
                    val selected = option == current
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected, role = Role.RadioButton) { onPick(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Colors.accent, unselectedColor = Colors.muted),
                        )
                        Spacer(Modifier.width(12.dp))
                        Glyph(c.icon, if (selected) Colors.accent else Colors.muted)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(stringResource(c.label), color = Colors.ink, fontSize = TextStyles.body)
                            Text(stringResource(c.description), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.accent) }
        },
    )
}
