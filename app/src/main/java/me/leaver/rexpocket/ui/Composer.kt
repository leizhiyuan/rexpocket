package me.leaver.rexpocket.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.QuickReplies

// The prompt composer, the quick replies' chips, and Settings › Quick replies. See
// docs/design/notifications.md, "Quick replies" and "The prompt composer".

/**
 * The drafts of the prompt composer, by host and pane: what was written and not sent stays with its
 * pane while the app runs, so leaving the composer, or the pane, loses nothing. Not stored: a draft
 * is for this sitting. The composer's Return after is the person's choice per host, kept in [prefs]
 * when there are any, so it does not go back on at every opening.
 */
class PromptDrafts(private val prefs: android.content.SharedPreferences? = null) {
    private val drafts = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val enter = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    operator fun get(key: String): String = drafts[key].orEmpty()

    operator fun set(key: String, text: String) {
        if (text.isEmpty()) drafts.remove(key) else drafts[key] = text
    }

    /** Whether the composer presses Return after sending, on [hostId]; on until the person turns it off. */
    fun enterAfter(hostId: String?): Boolean {
        val host = hostId.orEmpty()
        return enter[host] ?: prefs?.getBoolean(ENTER_KEY + host, true) ?: true
    }

    fun setEnterAfter(hostId: String?, on: Boolean) {
        val host = hostId.orEmpty()
        enter[host] = on
        prefs?.edit()?.putBoolean(ENTER_KEY + host, on)?.apply()
    }

    companion object {
        fun key(hostId: String?, blockId: String) = "${hostId.orEmpty()}/$blockId"

        private const val ENTER_KEY = "composer_enter_after_"
    }
}

internal const val COMPOSER_FIELD_TAG = "composer-field"
internal const val COMPOSER_SEND_TAG = "composer-send"
internal const val COMPOSER_ENTER_TAG = "composer-enter"
internal const val COMPOSER_CLEAR_TAG = "composer-clear"
internal const val COMPOSER_EDIT_REPLIES_TAG = "composer-edit-replies"
internal const val QUICK_REPLIES_RESTORE_TAG = "quick-replies-restore"
internal const val QUICK_REPLY_UNDO_TAG = "quick-reply-undo"
internal const val QUICK_REPLIES_ADD_TAG = "quick-replies-add"
internal const val QUICK_REPLY_FIELD_TAG = "quick-reply-field"
internal fun quickReplyRowTag(index: Int) = "quick-reply-$index"
internal fun quickReplyChipTag(index: Int) = "quick-reply-chip-$index"

/**
 * A longer prompt, written in a field of its own and sent to the pane in one paste - bracketed when
 * the program turned bracketed paste on, as Rex's paste event is, so an agent takes the lines as one
 * prompt rather than one per line - then Return, unless [enterAfter] is switched off; the switch
 * is the person's, kept by [onEnterAfter]. The draft is [draft]'s and goes back to it with every
 * change; Send clears it, or with [clearOnSend] off the caller does once Rex has the prompt, so a
 * send that fails keeps it. Clear can be
 * taken back: Restore puts the text back until something new is typed. Quick replies put their
 * text in, and [onEditReplies] opens Settings › Quick replies from here.
 */
@Composable
internal fun PromptComposer(
    /** The pane it goes to, as the person knows it. */
    target: String,
    draft: String,
    onDraft: (String) -> Unit,
    quickReplies: List<String>,
    onSend: (text: String, enter: Boolean) -> Unit,
    onDismiss: () -> Unit,
    enterAfter: Boolean = true,
    onEnterAfter: (Boolean) -> Unit = {},
    onEditReplies: (() -> Unit)? = null,
    /** False where the caller clears the draft itself once the prompt was taken. */
    clearOnSend: Boolean = true,
) {
    var text by remember { mutableStateOf(draft) }
    var enter by rememberSaveable { mutableStateOf(enterAfter) }
    // What Clear took, while Restore can still put it back.
    var cleared by rememberSaveable { mutableStateOf<String?>(null) }
    fun change(next: String) {
        text = next
        onDraft(next)
        if (next.isNotEmpty()) cleared = null
    }
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.composer_title)) {
        val sheet = this
        Column(Modifier.imePadding()) {
            Text(
                stringResource(R.string.composer_for, target),
                color = Colors.muted,
                fontSize = TextStyles.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            Column(Modifier.fillMaxWidth().testTag(COMPOSER_FIELD_TAG)) {
                Field(
                    hint = stringResource(R.string.composer_hint),
                    value = text,
                    autoFocus = true,
                    singleLine = false,
                    minLines = 5,
                    onChange = ::change,
                )
            }
            if (quickReplies.isNotEmpty()) {
                QuickReplyChips(quickReplies, insert = true) { reply -> change(joinReply(text, reply)) }
            }
            // The way to the quick replies from where they are used, with none or many.
            if (onEditReplies != null) {
                Text(
                    stringResource(R.string.quick_replies_edit_all),
                    color = Colors.accent,
                    fontSize = TextStyles.label,
                    modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.chip))
                        .clickable(role = Role.Button) { sheet.close(onEditReplies) }
                        .testTag(COMPOSER_EDIT_REPLIES_TAG)
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(Radius.card))
                    .background(Colors.surface)
                    .toggleable(value = enter, role = Role.Switch) {
                        enter = it
                        onEnterAfter(it)
                    }
                    .testTag(COMPOSER_ENTER_TAG)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.composer_enter_after), color = Colors.ink, fontSize = TextStyles.body)
                    if (!enter) Text(stringResource(R.string.composer_enter_after_body), color = Colors.muted, fontSize = TextStyles.label)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = enter, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = Colors.accent))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                val restore = cleared
                if (restore != null && text.isEmpty()) {
                    val spoken = stringResource(R.string.composer_restore_a11y)
                    RowButton(
                        stringResource(R.string.composer_restore),
                        { change(restore) },
                        modifier = Modifier.testTag(COMPOSER_CLEAR_TAG).semantics { contentDescription = spoken },
                    )
                } else {
                    RowButton(
                        stringResource(R.string.composer_clear),
                        {
                            val was = text
                            change("")
                            cleared = was
                        },
                        enabled = text.isNotEmpty(),
                        modifier = Modifier.testTag(COMPOSER_CLEAR_TAG),
                    )
                }
                Spacer(Modifier.width(8.dp))
                PrimaryButton(
                    stringResource(R.string.composer_send),
                    modifier = Modifier.weight(1f).testTag(COMPOSER_SEND_TAG),
                    enabled = text.isNotBlank(),
                ) {
                    val sent = text
                    if (clearOnSend) change("")
                    sheet.close { onSend(sent, enter) }
                }
            }
        }
    }
}

/** What a quick reply put into [text] makes: on a line of its own after what is there, or alone. */
internal fun joinReply(text: String, reply: String): String = when {
    text.isBlank() -> reply
    text.endsWith("\n") || text.endsWith(" ") -> text + reply
    else -> "$text $reply"
}

/**
 * The quick replies as chips, each a 48 dp target: [insert] ones put their text in a field (the
 * composer's, Reply's), the others send it. TalkBack says which, with the whole reply.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun QuickReplyChips(replies: List<String>, insert: Boolean, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        replies.forEachIndexed { i, reply ->
            val spoken = stringResource(if (insert) R.string.quick_reply_insert else R.string.quick_reply_send, reply)
            // The whole 48 dp takes the tap; the chip drawn in it is the size of its text.
            Box(
                Modifier.heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = { onPick(reply) })
                    .semantics { contentDescription = spoken }
                    .testTag(quickReplyChipTag(i)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    QuickReplies.short(reply, max = 24),
                    color = Colors.ink,
                    fontSize = TextStyles.label,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(Radius.chip)).background(Colors.key).padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/**
 * Settings › Quick replies: the list in its order, each row opening its edit page, with Move up and
 * Move down beside it (and as TalkBack actions); Add at the end. The edit page has the text, Save and
 * Delete. A page in the sheet rather than a dialog: the keyboard stays with the sheet's window.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun QuickRepliesSheet(replies: QuickReplies, onChange: (QuickReplies) -> Unit, onDismiss: () -> Unit) {
    // The reply being edited: its index, NEW for one being added, null on the list.
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    // The list as it was before the last delete, while Undo can put it back.
    var undo by remember { mutableStateOf<Pair<String, QuickReplies>?>(null) }
    val at = editing
    if (at != null) {
        val adding = at == NEW
        var text by rememberSaveable(at) { mutableStateOf(if (adding) "" else replies.items.getOrNull(at).orEmpty()) }
        fun save() {
            onChange(if (adding) replies.add(text) else replies.edit(at, text))
            editing = null
        }
        ActionSheet(
            onDismiss = onDismiss,
            title = stringResource(if (adding) R.string.quick_replies_add else R.string.quick_replies_edit),
            onBack = { editing = null },
        ) {
            Column(Modifier.imePadding().padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth().testTag(QUICK_REPLY_FIELD_TAG)) {
                    Field(stringResource(R.string.quick_replies_field), text, autoFocus = true, singleLine = false, minLines = 2) { text = it }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (!adding) {
                        RowButton(stringResource(R.string.action_delete), {
                            replies.items.getOrNull(at)?.let { undo = it to replies }
                            onChange(replies.delete(at))
                            editing = null
                        })
                        Spacer(Modifier.width(8.dp))
                    }
                    PrimaryButton(stringResource(R.string.action_save), modifier = Modifier.weight(1f), enabled = QuickReplies.clean(text) != null) { save() }
                }
            }
        }
        return
    }
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.quick_replies_title)) {
        Text(
            stringResource(R.string.quick_replies_body),
            color = Colors.muted,
            fontSize = TextStyles.label,
            lineHeight = labelLeading,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp),
        )
        // A delete says so, with the way back, until the list changes again.
        undo?.let { (gone, before) ->
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.card))
                    .background(Colors.surface).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.quick_reply_deleted, QuickReplies.short(gone, max = 24)),
                    color = Colors.ink,
                    fontSize = TextStyles.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                )
                Text(
                    stringResource(R.string.action_undo),
                    color = Colors.accent,
                    fontSize = TextStyles.label,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.chip))
                        .clickable(role = Role.Button) {
                            onChange(before)
                            undo = null
                        }
                        .testTag(QUICK_REPLY_UNDO_TAG)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
        }
        Group(icons = false) {
            if (replies.items.isEmpty()) {
                Text(
                    stringResource(R.string.quick_replies_none),
                    color = Colors.muted,
                    fontSize = TextStyles.body,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(16.dp),
                )
            }
            replies.items.forEachIndexed { i, reply ->
                QuickReplyRow(
                    reply = reply,
                    index = i,
                    last = i == replies.items.lastIndex,
                    onEdit = { editing = i },
                    onMove = { by -> onChange(replies.move(i, by)) },
                )
            }
        }
        Group(icons = true) {
            SheetItem(
                label = stringResource(R.string.quick_replies_add),
                icon = R.drawable.ic_add,
                supporting = if (replies.canAdd) null else stringResource(R.string.quick_replies_full),
                enabled = replies.canAdd,
                keepOpen = true,
                opens = true,
                onClick = { editing = NEW },
            )
            // The app's own replies, put back at the end of the list, when any have gone.
            val missing = QuickReplies.DEFAULTS.filter { it !in replies.items }
            if (missing.isNotEmpty()) {
                SheetItem(
                    label = stringResource(R.string.quick_replies_restore),
                    icon = R.drawable.ic_restart,
                    supporting = stringResource(R.string.quick_replies_restore_hint),
                    enabled = replies.canAdd,
                    keepOpen = true,
                    modifier = Modifier.testTag(QUICK_REPLIES_RESTORE_TAG),
                    onClick = {
                        undo = null
                        onChange(missing.fold(replies) { list, reply -> list.add(reply) })
                    },
                )
            }
        }
    }
}

private const val NEW = -1

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun QuickReplyRow(reply: String, index: Int, last: Boolean, onEdit: () -> Unit, onMove: (Int) -> Unit) {
    val name = QuickReplies.short(reply)
    val up = stringResource(R.string.arrange_move_up, name)
    val down = stringResource(R.string.arrange_move_down, name)
    val edit = stringResource(R.string.quick_replies_edit)
    val actions = buildList {
        if (index > 0) add(CustomAccessibilityAction(up) { onMove(-1); true })
        if (!last) add(CustomAccessibilityAction(down) { onMove(1); true })
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            reply,
            color = Colors.ink,
            fontSize = TextStyles.body,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                .combinedClickable(role = Role.Button, onClickLabel = edit, onClick = onEdit)
                .semantics { customActions = actions }
                .testTag(quickReplyRowTag(index))
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
        )
        // At an end, greyed out and in place, so the rows' buttons line up.
        RoundButton(R.drawable.ic_arrow_up, up, { onMove(-1) }, size = 48.dp, tint = if (index > 0) Colors.muted else Colors.line, enabled = index > 0)
        RoundButton(R.drawable.ic_arrow_down, down, { onMove(1) }, size = 48.dp, tint = if (!last) Colors.muted else Colors.line, enabled = !last)
        Spacer(Modifier.width(4.dp))
    }
}

/** The quick replies' detail on Settings' row: the first few, as they read. */
internal fun quickRepliesDetail(replies: QuickReplies): String? =
    replies.items.take(3).joinToString(", ") { QuickReplies.short(it, max = 12) }.ifEmpty { null }
