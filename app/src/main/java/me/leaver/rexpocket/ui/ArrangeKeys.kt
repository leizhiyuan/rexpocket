package me.leaver.rexpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.KeyGroup
import me.leaver.rexpocket.core.KeyKind
import me.leaver.rexpocket.core.KeyPreset
import me.leaver.rexpocket.core.Pill
import kotlin.math.roundToInt

// Settings › Terminal controls › Arrange keys: the key bar's keys, its key groups (Fn and the panels
// like it), and the pages to add keys and make groups. See docs/design/terminal-controls.md, 4.1.

/** The pages of Arrange keys, each shown in the sheet on its own; back goes up one. */
private sealed interface ArrangePage {
    data object Main : ArrangePage

    /** The catalogue for the key pill ([group] null), the right pill, or [group]'s panel. */
    data class Add(val pill: Pill, val group: String? = null) : ArrangePage

    data class Group(val id: String) : ArrangePage

    data object NewGroup : ArrangePage

    /** A name for a new empty group ([id] null), or for group [id]. */
    data class Name(val id: String?) : ArrangePage
}

/** A question over the page: a name for a group, or whether to delete or reset. */
private sealed interface ArrangeDialog {
    data class Delete(val id: String) : ArrangeDialog

    data object Reset : ArrangeDialog
}

/**
 * Arrange keys: the key pill's keys in their order, the key groups, the right pill's keys, and
 * Reset. A key row has a handle to drag it to its place, up and down for one step (and for
 * TalkBack, where a drag is awkward), and remove; the keyboard key has a lock that says why it
 * stays. Every change applies at once. Each group, and each place's "Add keys", is a page of its own.
 */
@Composable
internal fun ArrangeKeysSheet(arrangement: KeyArrangement, onArrangement: (KeyArrangement) -> Unit, onDismiss: () -> Unit) {
    val current by rememberUpdatedState(arrangement)
    val change by rememberUpdatedState(onArrangement)
    var page by remember { mutableStateOf<ArrangePage>(ArrangePage.Main) }
    var dialog by remember { mutableStateOf<ArrangeDialog?>(null) }
    // A group deleted while its page or catalogue was open: back to the list.
    val shown = when (val p = page) {
        is ArrangePage.Group -> if (arrangement.group(p.id) == null) ArrangePage.Main else p
        is ArrangePage.Add -> if (p.group != null && arrangement.group(p.group) == null) ArrangePage.Main else p
        is ArrangePage.Name -> if (p.id != null && arrangement.group(p.id) == null) ArrangePage.Main else p
        else -> p
    }
    when (shown) {
        ArrangePage.Main -> MainPage(arrangement, { change(it(current)) }, onPage = { page = it }, onDialog = { dialog = it }, onDismiss = onDismiss)
        is ArrangePage.Add -> AddKeysPage(arrangement, shown, { change(it(current)) }) {
            page = if (shown.group != null) ArrangePage.Group(shown.group) else ArrangePage.Main
        }
        is ArrangePage.Group -> GroupPage(arrangement, arrangement.group(shown.id)!!, { change(it(current)) }, onPage = { page = it }, onDialog = { dialog = it }) {
            page = ArrangePage.Main
        }
        ArrangePage.NewGroup -> NewGroupPage(arrangement, { change(it(current)) }, onPage = { page = it }) {
            page = ArrangePage.Main
        }
        is ArrangePage.Name -> {
            val back = { page = if (shown.id != null) ArrangePage.Group(shown.id) else ArrangePage.NewGroup }
            NamePage(
                title = stringResource(if (shown.id == null) R.string.arrange_empty_group else R.string.arrange_rename),
                initial = shown.id?.let { arrangement.group(it)?.name }.orEmpty(),
                onBack = back,
            ) { name ->
                if (shown.id == null) {
                    val id = current.newGroupId()
                    val next = current.addGroup(name)
                    change(next)
                    page = if (next.group(id) != null) ArrangePage.Group(id) else ArrangePage.Main
                } else {
                    change(current.renameGroup(shown.id, name))
                    page = ArrangePage.Group(shown.id)
                }
            }
        }
    }
    when (val d = dialog) {
        null -> Unit
        is ArrangeDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.arrange_delete_title, current.group(d.id)?.name.orEmpty()),
            body = stringResource(R.string.arrange_delete_body),
            action = stringResource(R.string.action_delete),
            onDismiss = { dialog = null },
        ) {
            change(current.deleteGroup(d.id))
            page = ArrangePage.Main
        }
        ArrangeDialog.Reset -> ConfirmDialog(
            title = stringResource(R.string.arrange_reset_title),
            body = stringResource(R.string.arrange_reset_body),
            action = stringResource(R.string.arrange_reset_confirm),
            onDismiss = { dialog = null },
        ) { change(KeyArrangement()) }
    }
}

/** A change to the arrangement, made on the one that is current when it runs. */
private typealias Change = ((KeyArrangement) -> KeyArrangement) -> Unit

@Composable
private fun MainPage(
    arrangement: KeyArrangement,
    change: Change,
    onPage: (ArrangePage) -> Unit,
    onDialog: (ArrangeDialog) -> Unit,
    onDismiss: () -> Unit,
) {
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.arrange_title), onBack = onDismiss) {
        Group(stringResource(R.string.arrange_group_keys)) {
            val order = arrangement.keys
            order.forEachIndexed { index, barKey ->
                key(barKey) {
                    ArrangeRow(
                        barKey = barKey,
                        name = keyName(barKey, arrangement),
                        supporting = (barKey as? BarKey.Group)?.let { arrangement.group(it.id) }?.let { groupSummary(it, onBar = true) },
                        first = index == 0,
                        last = index == order.lastIndex,
                        onMove = { by -> change { it.move(barKey, by) } },
                        onRemove = { change { it.remove(barKey) } },
                    )
                }
            }
            SheetItem(stringResource(R.string.arrange_add_keys), icon = R.drawable.ic_add, keepOpen = true, opens = true) { onPage(ArrangePage.Add(Pill.Keys)) }
        }
        Group(stringResource(R.string.arrange_group_groups)) {
            for (group in arrangement.groupsInOrder()) {
                key(group.id) {
                    SheetItem(
                        group.name,
                        icon = R.drawable.ic_command,
                        supporting = groupSummary(group, onBar = arrangement.has(group.key)),
                        keepOpen = true,
                        opens = true,
                    ) { onPage(ArrangePage.Group(group.id)) }
                }
            }
            if (arrangement.canAddGroup) {
                SheetItem(stringResource(R.string.arrange_new_group), icon = R.drawable.ic_add, keepOpen = true, opens = true) { onPage(ArrangePage.NewGroup) }
            } else {
                UnavailableItem(
                    stringResource(R.string.arrange_new_group),
                    R.drawable.ic_add,
                    stringResource(R.string.arrange_new_group_limit, KeyArrangement.MAX_GROUPS),
                )
            }
        }
        Group(stringResource(R.string.arrange_group_rest)) {
            val order = arrangement.rest
            order.forEachIndexed { index, barKey ->
                key(barKey) {
                    ArrangeRow(
                        barKey = barKey,
                        name = keyName(barKey, arrangement),
                        supporting = if (barKey.removable) null else stringResource(R.string.arrange_always),
                        first = index == 0,
                        last = index == order.lastIndex,
                        onMove = { by -> change { it.move(barKey, by) } },
                        onRemove = { change { it.remove(barKey) } },
                    )
                }
            }
            if (arrangement.catalogue(Pill.Rest).any { !arrangement.has(it) }) {
                SheetItem(stringResource(R.string.arrange_add_keys), icon = R.drawable.ic_add, keepOpen = true, opens = true) { onPage(ArrangePage.Add(Pill.Rest)) }
            }
        }
        Group {
            SheetItem(stringResource(R.string.arrange_reset), Colors.accent, icon = R.drawable.ic_restart, keepOpen = true) {
                // Groups the person made would go: ask first. A bar with only Fn resets at once.
                if (arrangement.groups.any { it.id != BarKey.FN_GROUP }) onDialog(ArrangeDialog.Reset) else change { KeyArrangement() }
            }
        }
    }
}

/** A group's page: its keys in order, Add keys, then whether its key is on the bar, Rename, Delete. */
@Composable
private fun GroupPage(
    arrangement: KeyArrangement,
    group: KeyGroup,
    change: Change,
    onPage: (ArrangePage) -> Unit,
    onDialog: (ArrangeDialog) -> Unit,
    onBack: () -> Unit,
) {
    ActionSheet(onDismiss = onBack, title = group.name, onBack = onBack) {
        Group(stringResource(R.string.arrange_group_panel)) {
            if (group.keys.isEmpty()) {
                Text(
                    stringResource(R.string.arrange_group_empty),
                    color = Colors.muted,
                    fontSize = TextStyles.label,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
            group.keys.forEachIndexed { index, barKey ->
                key(barKey) {
                    ArrangeRow(
                        barKey = barKey,
                        name = keyName(barKey, arrangement),
                        supporting = if (arrangement.has(barKey)) stringResource(R.string.arrange_also_on_pill) else null,
                        first = index == 0,
                        last = index == group.keys.lastIndex,
                        onMove = { by -> change { it.moveInGroup(group.id, barKey, by) } },
                        onRemove = { change { it.removeFromGroup(group.id, barKey) } },
                    )
                }
            }
            SheetItem(stringResource(R.string.arrange_add_keys), icon = R.drawable.ic_add, keepOpen = true, opens = true) {
                onPage(ArrangePage.Add(Pill.Keys, group.id))
            }
        }
        Group {
            val onBar = arrangement.has(group.key)
            SheetItem(
                stringResource(R.string.arrange_on_bar),
                icon = R.drawable.ic_keyboard,
                detail = stringResource(if (onBar) R.string.arrange_on else R.string.arrange_off),
                keepOpen = true,
            ) { change { if (onBar) it.remove(group.key) else it.add(group.key) } }
            SheetItem(stringResource(R.string.arrange_rename), icon = R.drawable.ic_edit, keepOpen = true, opens = true) { onPage(ArrangePage.Name(group.id)) }
            SheetItem(stringResource(R.string.arrange_delete), Colors.danger, icon = R.drawable.ic_delete, keepOpen = true) {
                onDialog(ArrangeDialog.Delete(group.id))
            }
        }
    }
}

/** New group: the presets, each made with one tap (or opened, once made), and an empty one. */
@Composable
private fun NewGroupPage(
    arrangement: KeyArrangement,
    change: Change,
    onPage: (ArrangePage) -> Unit,
    onBack: () -> Unit,
) {
    ActionSheet(onDismiss = onBack, title = stringResource(R.string.arrange_new_group), onBack = onBack) {
        Group(stringResource(R.string.arrange_presets)) {
            for (preset in KeyPreset.entries) {
                val made = arrangement.group(preset.id) != null
                val faces = preset.keys().map { keyFace(it) }
                SheetItem(
                    preset.title,
                    icon = R.drawable.ic_command,
                    supporting = faces.joinToString(" "),
                    detail = if (made) stringResource(R.string.arrange_preset_added) else null,
                    keepOpen = true,
                ) {
                    if (!made) change { it.addPreset(preset) }
                    onPage(ArrangePage.Group(preset.id))
                }
            }
        }
        Group {
            SheetItem(stringResource(R.string.arrange_empty_group), icon = R.drawable.ic_add, keepOpen = true, opens = true) { onPage(ArrangePage.Name(null)) }
        }
    }
}

/**
 * Add keys: every key that may go in the place, as chips under their headings. A chip is a toggle -
 * checked when the key is there - and the page stays open, so a group fills with a run of taps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddKeysPage(arrangement: KeyArrangement, page: ArrangePage.Add, change: Change, onBack: () -> Unit) {
    val group = page.group?.let { arrangement.group(it) }
    val place = group?.name ?: stringResource(if (page.pill == Pill.Keys) R.string.arrange_group_keys else R.string.arrange_group_rest)
    ActionSheet(onDismiss = onBack, title = stringResource(R.string.arrange_add_to, place), onBack = onBack) {
        val keys = arrangement.catalogue(page.pill, page.group)
        for ((kind, ofKind) in keys.groupBy { it.kind }) {
            Text(
                kindName(kind),
                color = Colors.muted,
                fontSize = TextStyles.label,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp),
            )
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (barKey in ofKind) {
                    val there = if (group != null) barKey in group.keys else arrangement.has(barKey)
                    KeyChip(keyFace(barKey, arrangement), keyName(barKey, arrangement), barKey.pref, there) { on ->
                        change {
                            when {
                                group != null && on -> it.addToGroup(group.id, barKey)
                                group != null -> it.removeFromGroup(group.id, barKey)
                                on -> it.add(barKey)
                                else -> it.remove(barKey)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun KeyChip(face: String, name: String, pref: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val state = stringResource(if (checked) R.string.arrange_chip_added else R.string.arrange_chip_not_added)
    Box(
        Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
            .clip(RoundedCornerShape(Radius.chip))
            .background(if (checked) Colors.keyOn else Colors.surface)
            .border(1.dp, if (checked) Colors.accent else Colors.line, RoundedCornerShape(Radius.chip))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChecked)
            .semantics { contentDescription = name; stateDescription = state }
            .testTag("chip-$pref")
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            face,
            color = if (checked) Colors.accent else Colors.ink,
            fontSize = TextStyles.body,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * One key in a list. Dragged by its handle, the row follows the finger and takes the place of each
 * row it passes half of; the handle takes the drag as soon as it passes the touch slop, before the
 * sheet's scroll or its drag to close can. [supporting] is a line under the name.
 */
@Composable
private fun ArrangeRow(
    barKey: BarKey,
    name: String,
    supporting: String?,
    first: Boolean,
    last: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val density = LocalDensity.current
    // A place in the list: this row and the hairline under it.
    var rowPx by remember { mutableFloatStateOf(with(density) { (ROW_HEIGHT + 1.dp).toPx() }) }
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val move by rememberUpdatedState(onMove)
    val canUp by rememberUpdatedState(!first)
    val canDown by rememberUpdatedState(!last)
    Row(
        Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT)
            .onSizeChanged { rowPx = it.height + with(density) { 1.dp.toPx() } }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) offset else 0f }
            .testTag("arrange-${barKey.pref}")
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dragLabel = stringResource(R.string.arrange_drag, name)
        Box(
            Modifier.size(TOUCH)
                .testTag("arrange-handle-${barKey.pref}")
                .semantics { contentDescription = dragLabel }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            offset = 0f
                        },
                        onDragEnd = { dragging = false; offset = 0f },
                        onDragCancel = { dragging = false; offset = 0f },
                    ) { change, dy ->
                        change.consume()
                        offset += dy
                        val steps = (offset / rowPx).roundToInt()
                        val allowed = when {
                            steps < 0 && !canUp -> 0
                            steps > 0 && !canDown -> 0
                            else -> steps.coerceIn(-1, 1)
                        }
                        if (allowed != 0) {
                            move(allowed)
                            offset -= allowed * rowPx
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Glyph(R.drawable.ic_drag_handle, Colors.muted) }
        Column(Modifier.weight(1f).padding(start = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Text(name, color = Colors.ink, fontSize = TextStyles.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (supporting != null) {
                Text(supporting, color = Colors.muted, fontSize = TextStyles.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        StepButton(R.drawable.ic_arrow_up, stringResource(R.string.arrange_move_up, name), enabled = !first) { onMove(-1) }
        StepButton(R.drawable.ic_arrow_down, stringResource(R.string.arrange_move_down, name), enabled = !last) { onMove(1) }
        if (barKey.removable) {
            val label = stringResource(R.string.arrange_remove, name)
            Box(
                Modifier.size(TOUCH).clickable(role = Role.Button, onClick = onRemove).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Glyph(R.drawable.ic_remove_circle, Colors.muted) }
        } else {
            // The keyboard key stays: without it a keyboard put away has no way back. The lock says so
            // when tapped, as the row's own line does.
            WhyNot(
                description = stringResource(R.string.arrange_cannot_remove, name, stringResource(R.string.arrange_always)),
                reason = stringResource(R.string.arrange_always),
                tag = "arrange-locked-${barKey.pref}",
            )
        }
    }
}

/**
 * A control that cannot act, and says why: a lock in the place of the control, whose tap or long
 * press shows [reason] in a tooltip, and which TalkBack reads as [description] - the platform's way
 * to explain a disabled control rather than leave a tap doing nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhyNot(description: String, reason: String, tag: String) {
    val state = rememberTooltipState()
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(reason) } },
        state = state,
    ) {
        Box(
            Modifier.size(TOUCH)
                .clickable(role = Role.Button) { scope.launch { state.show() } }
                .semantics { contentDescription = description }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) { Glyph(R.drawable.ic_lock, Colors.muted, size = 20.dp) }
    }
}

/** A row whose action is not available now, with the reason under its label, and read as disabled. */
@Composable
private fun UnavailableItem(label: String, icon: Int, reason: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) { disabled() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, Colors.line, size = 24.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Colors.muted, fontSize = TextStyles.body)
            Text(reason, color = Colors.muted, fontSize = TextStyles.label)
        }
    }
}

@Composable
private fun StepButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(TOUCH)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, if (enabled) Colors.muted else Colors.line) }
}

/**
 * A group's name, typed on a page of the sheet rather than in a dialog over it: a dialog would take
 * the keyboard from the sheet's window and lose it as the sheet goes. Save is its last row.
 */
@Composable
private fun NamePage(title: String, initial: String, onBack: () -> Unit, onName: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val clean = KeyArrangement.cleanName(text)
    ActionSheet(onDismiss = onBack, title = title, onBack = onBack) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Field(stringResource(R.string.field_name), text, autoFocus = true) { text = it.take(KeyArrangement.MAX_NAME + 4) }
            Text(
                stringResource(R.string.arrange_name_hint, KeyArrangement.MAX_NAME),
                color = Colors.muted,
                fontSize = TextStyles.label,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Group {
            if (clean != null) {
                SheetItem(stringResource(R.string.action_save), Colors.accent, icon = R.drawable.ic_check, keepOpen = true) { onName(clean) }
            } else {
                UnavailableItem(stringResource(R.string.action_save), R.drawable.ic_check, stringResource(R.string.arrange_name_needed))
            }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(title, color = Colors.ink) },
        text = { Text(body, color = Colors.muted) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(action, color = Colors.danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) } },
    )
}

/** "25 keys · on the key bar", or why its key is not drawn. */
@Composable
private fun groupSummary(group: KeyGroup, onBar: Boolean): String = when {
    group.keys.isEmpty() -> stringResource(R.string.arrange_group_summary_empty)
    else -> stringResource(
        if (onBar) R.string.arrange_group_summary_on else R.string.arrange_group_summary_off,
        pluralStringResource(R.plurals.arrange_keys, group.keys.size, group.keys.size),
    )
}

@Composable
private fun kindName(kind: KeyKind): String = stringResource(
    when (kind) {
        KeyKind.Bar -> R.string.arrange_kind_bar
        KeyKind.Group -> R.string.arrange_group_groups
        KeyKind.Rest -> R.string.arrange_group_rest
        KeyKind.Desk -> R.string.arrange_kind_desk
        KeyKind.Arrow -> R.string.arrange_kind_arrows
        KeyKind.Control -> R.string.arrange_kind_control
        KeyKind.Symbol -> R.string.arrange_kind_symbols
        KeyKind.LetterOrDigit -> R.string.arrange_kind_letters
    },
)

/** What a key shows in a chip: its face on the bar, or its name where the bar shows a mark. */
@Composable
internal fun keyFace(key: BarKey, arrangement: KeyArrangement? = null): String = when (key) {
    is BarKey.Desk -> deskLabel(key.key)
    is BarKey.Arrow -> stringResource(arrowFace(key.code).second)
    is BarKey.Chord -> chordLabel(key.letter)
    is BarKey.Typed -> typedFace(key.char)
    BarKey.Slash -> "/"
    else -> keyName(key, arrangement)
}

/** A key's name in the lists, and what TalkBack calls it there. */
@Composable
internal fun keyName(key: BarKey, arrangement: KeyArrangement? = null): String = when (key) {
    BarKey.Escape -> stringResource(R.string.key_escape)
    BarKey.CtrlC -> stringResource(R.string.key_ctrl_c)
    BarKey.Tab -> stringResource(R.string.key_tab)
    BarKey.Enter -> stringResource(R.string.action_enter)
    BarKey.Ctrl -> stringResource(R.string.key_ctrl)
    BarKey.Shift -> stringResource(R.string.key_shift)
    BarKey.Alt -> stringResource(R.string.key_alt)
    BarKey.Paste -> stringResource(R.string.key_paste)
    BarKey.Attach -> stringResource(R.string.upload_attach)
    BarKey.Slash -> stringResource(R.string.arrange_slash)
    BarKey.Arrows -> stringResource(R.string.key_arrows)
    BarKey.Keyboard -> stringResource(R.string.key_keyboard)
    BarKey.FullScreen -> stringResource(R.string.action_full_screen)
    is BarKey.Group -> arrangement?.group(key.id)?.name ?: key.id
    is BarKey.Desk -> deskLabel(key.key)
    is BarKey.Arrow -> stringResource(
        when (key.code) {
            "arrow_up" -> R.string.arrange_arrow_up
            "arrow_down" -> R.string.arrange_arrow_down
            "arrow_left" -> R.string.arrange_arrow_left
            else -> R.string.arrange_arrow_right
        },
    )
    is BarKey.Chord -> chordLabel(key.letter)
    is BarKey.Typed -> if (key.char == ' ') stringResource(R.string.key_space) else key.char.toString()
}

private val ROW_HEIGHT = 56.dp

/**
 * A row's handle, up, down and remove: the platform's 48 dp touch target each. It also puts the
 * handle's mark on the same 16 dp keyline as the icons of the Add keys row under the list.
 */
private val TOUCH = 48.dp
