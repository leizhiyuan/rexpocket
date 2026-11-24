package me.leaver.rexpocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.RexActions
import me.leaver.rexpocket.core.RexPrompt
import org.json.JSONObject

/** Test tag of a pick question's item, by id. */
internal fun rexPromptItemTag(id: String) = "rex-prompt-item-$id"

/**
 * A question one of the person's Rex actions asks (`rex.ui.input`, `rex.ui.pick`), as Rex's palette
 * puts it: its title, what it is for, and a line of text or a list to choose from - one item, any
 * number with [RexPrompt.multiple], or typed text too with [RexPrompt.allowOther]. Cancel closes it
 * for every device; it closes by itself once its time is up.
 */
@Composable
internal fun RexPromptDialog(prompt: RexPrompt, actionTitle: String?, onAnswer: (JSONObject) -> Unit) {
    LaunchedEffect(prompt.callId) {
        delay(prompt.timeoutMs.coerceAtLeast(1_000))
        onAnswer(RexActions.cancelled())
    }
    var text by remember(prompt.callId) { mutableStateOf(prompt.value) }
    var chosen by remember(prompt.callId) { mutableStateOf(prompt.items.filter { it.selected }.map { it.id }.toSet()) }
    val pick = prompt.kind == "pick"
    val typed = !pick || prompt.allowOther
    val use = prompt.confirm ?: if (pick && prompt.multiple) stringResource(R.string.rex_prompt_done) else stringResource(R.string.rex_prompt_use, text)
    fun submitText() {
        onAnswer(RexActions.text(text))
    }
    AlertDialog(
        onDismissRequest = { onAnswer(RexActions.cancelled()) },
        containerColor = Colors.surface,
        title = { Text(prompt.title.ifBlank { actionTitle.orEmpty() }, color = Colors.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (actionTitle != null && prompt.title.isNotBlank()) {
                    Text(actionTitle, color = Colors.muted, fontSize = TextStyles.label, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (typed) {
                    Field(
                        stringResource(if (pick) R.string.rex_prompt_other else R.string.rex_prompt_answer),
                        text,
                        autoFocus = !pick,
                        placeholder = prompt.placeholder,
                        autoCorrect = false,
                        imeAction = ImeAction.Done,
                        onDone = { submitText() },
                    ) { text = it }
                }
                if (pick) {
                    for (item in prompt.items) {
                        val on = item.id in chosen
                        val row = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(rexPromptItemTag(item.id))
                        Row(
                            if (prompt.multiple) {
                                row.toggleable(value = on, role = Role.Checkbox) { chosen = if (it) chosen + item.id else chosen - item.id }
                            } else {
                                row.clickable(role = Role.Button) { onAnswer(RexActions.ids(listOf(item.id))) }
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (prompt.multiple) {
                                Checkbox(checked = on, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Colors.accent))
                            }
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(item.title, color = Colors.ink, fontSize = TextStyles.body, fontWeight = FontWeight.Medium)
                                item.subtitle?.let { Text(it, color = Colors.muted, fontSize = TextStyles.label) }
                            }
                            item.detail?.let { Text(it, color = Colors.muted, fontSize = TextStyles.label, modifier = Modifier.padding(start = 8.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                pick && prompt.multiple -> TextButton(onClick = { onAnswer(RexActions.ids(prompt.items.map { it.id }.filter { it in chosen })) }) {
                    Text("$use · ${chosen.size}", color = Colors.accent)
                }
                typed -> TextButton(onClick = { submitText() }, enabled = !pick || text.isNotEmpty()) {
                    Text(use, color = if (!pick || text.isNotEmpty()) Colors.accent else Colors.muted)
                }
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(RexActions.cancelled()) }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) } },
    )
}
