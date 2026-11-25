package me.leaver.rexpocket.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.LaunchTemplate
import me.leaver.rexpocket.data.SavedHost

// A host's launch templates on the home: "New tab from template" in the host's and a session's
// sheet lists them, a tap runs one, and Edit templates adds, changes, orders and deletes them (a
// delete with Undo). See docs/design/home.md, "Launch templates".

/** Test tags around the template dialog's fields, which a test types into. */
internal const val TEMPLATE_NAME_TAG = "template-name"
internal const val TEMPLATE_DIR_TAG = "template-directory"
internal const val TEMPLATE_COMMAND_TAG = "template-command"

/** Which template sheet or dialog is open, for one host; survives a rotation. */
@Stable
internal class TemplateSheets {
    /** The host whose templates are shown, and the session a run opens its tab in (null: a new session). */
    var host by mutableStateOf<String?>(null)
    var session by mutableStateOf<String?>(null)
    var page by mutableStateOf(Page.None)
    /** The template of [Page.One] and [Page.Edit]; null in [Page.Edit] for a new one. */
    var template by mutableStateOf<String?>(null)
    /** Where the editor goes back to: the list to run from, or the one to manage. */
    var back by mutableStateOf(Page.Run)

    enum class Page { None, Run, Manage, One, Edit }

    fun open(hostId: String, sessionId: String?) {
        host = hostId
        session = sessionId
        template = null
        page = Page.Run
    }

    fun edit(templateId: String?, from: Page) {
        template = templateId
        back = from
        page = Page.Edit
    }

    fun close() {
        page = Page.None
        template = null
    }

    companion object {
        val Saver: Saver<TemplateSheets, List<String>> = Saver(
            save = { listOf(it.host.orEmpty(), it.session.orEmpty(), it.page.name, it.template.orEmpty(), it.back.name) },
            restore = { s ->
                TemplateSheets().apply {
                    host = s[0].ifEmpty { null }
                    session = s[1].ifEmpty { null }
                    page = Page.entries.firstOrNull { it.name == s[2] } ?: Page.None
                    template = s[3].ifEmpty { null }
                    back = Page.entries.firstOrNull { it.name == s[4] } ?: Page.Run
                }
            },
        )
    }
}

/** What a template does, in one line: "~/src/api · claude". */
internal fun templateSummary(t: LaunchTemplate): String =
    listOf(t.directory.trim(), t.command.trim()).filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * The open template sheet or dialog of [sheets], if any. [templatesOf] gives a host's templates;
 * [onSet] replaces them; [onRun] runs one; [snack] says what changed, with an Undo.
 */
@Composable
internal fun TemplateSheetsHost(
    sheets: TemplateSheets,
    hosts: List<SavedHost>,
    templatesOf: (String) -> List<LaunchTemplate>,
    /** A session's name, or null once it is gone: a run then makes a new session. */
    sessionName: (hostId: String, sessionId: String) -> String?,
    onSet: (hostId: String, List<LaunchTemplate>) -> Unit,
    onRun: (SavedHost, sessionId: String?, LaunchTemplate) -> Unit,
    snack: (message: String, action: String?, onAction: () -> Unit) -> Unit,
) {
    val page = sheets.page
    if (page == TemplateSheets.Page.None) return
    val context = LocalContext.current
    val host = hosts.firstOrNull { it.id == sheets.host }
    if (host == null) {
        LaunchedEffect(sheets.host) { sheets.close() }
        return
    }
    val list = templatesOf(host.id)
    val one = list.firstOrNull { it.id == sheets.template }
    // A sheet's item runs once its sheet has gone (Sheets.kt): the sheet closes only when the item
    // did not move on to another page.
    fun dismissed(of: TemplateSheets.Page, to: TemplateSheets.Page = TemplateSheets.Page.None) {
        if (sheets.page == of) {
            if (to == TemplateSheets.Page.None) sheets.close() else sheets.page = to
        }
    }
    when (page) {
        TemplateSheets.Page.Run -> {
            val where = sheets.session?.let { sessionName(host.id, it) }
            // The session the sheet was opened for, while it is still there; a new one otherwise.
            val runIn = sheets.session?.takeIf { where != null }
            RowActionsSheet(
                stringResource(R.string.template_new_tab),
                listOf(
                    ActionGroup(
                        list.map { t -> RowAction(t.name, R.drawable.ic_command, detail = templateSummary(t)) { onRun(host, runIn, t) } },
                        caption = if (list.isEmpty()) {
                            null
                        } else {
                            where?.let { stringResource(R.string.template_in_session, it) } ?: stringResource(R.string.template_in_new_session, host.label)
                        },
                    ),
                    ActionGroup(
                        listOfNotNull(
                            RowAction(stringResource(R.string.template_add), R.drawable.ic_add) { sheets.edit(null, TemplateSheets.Page.Run) },
                            RowAction(stringResource(R.string.template_manage), R.drawable.ic_edit) { sheets.page = TemplateSheets.Page.Manage }
                                .takeIf { list.isNotEmpty() },
                        ),
                        caption = stringResource(R.string.template_none).takeIf { list.isEmpty() },
                    ),
                ),
            ) { dismissed(TemplateSheets.Page.Run) }
        }
        TemplateSheets.Page.Manage -> RowActionsSheet(
            stringResource(R.string.template_manage_title, host.label),
            listOf(
                ActionGroup(
                    list.map { t ->
                        RowAction(t.name, R.drawable.ic_command, detail = templateSummary(t)) {
                            sheets.template = t.id
                            sheets.page = TemplateSheets.Page.One
                        }
                    },
                ),
                ActionGroup(listOf(RowAction(stringResource(R.string.template_add), R.drawable.ic_add) { sheets.edit(null, TemplateSheets.Page.Manage) })),
            ),
        ) { dismissed(TemplateSheets.Page.Manage) }
        TemplateSheets.Page.One -> {
            if (one == null) {
                LaunchedEffect(sheets.template) { sheets.page = TemplateSheets.Page.Manage }
                return
            }
            val index = list.indexOf(one)
            RowActionsSheet(
                one.name,
                listOfNotNull(
                    ActionGroup(listOf(RowAction(stringResource(R.string.template_edit), R.drawable.ic_edit) { sheets.edit(one.id, TemplateSheets.Page.Manage) })),
                    moveGroup(index, list.size, null) { to ->
                        val next = list.toMutableList()
                        next.add(to.coerceIn(0, next.size - 1), next.removeAt(index))
                        onSet(host.id, next)
                    },
                    ActionGroup(
                        listOf(
                            RowAction(stringResource(R.string.template_delete), R.drawable.ic_delete, danger = true) {
                                val before = list
                                onSet(host.id, list.filter { it.id != one.id })
                                // The sheets go, so the snackbar's Undo is not under them.
                                sheets.close()
                                snack(context.getString(R.string.template_deleted, one.name), context.getString(R.string.action_undo)) { onSet(host.id, before) }
                            },
                        ),
                    ),
                ),
            ) { dismissed(TemplateSheets.Page.One, TemplateSheets.Page.Manage) }
        }
        TemplateSheets.Page.Edit -> TemplateDialog(initial = one, onDismiss = { sheets.page = sheets.back }) { saved ->
            val next = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it } else list + saved
            onSet(host.id, next.take(LaunchTemplate.MAX_ITEMS))
            sheets.template = null
            sheets.page = sheets.back
        }
        TemplateSheets.Page.None -> Unit
    }
}

/**
 * A template's name, directory and command. Save is there once it has a name and a directory or a
 * command; the directory may start with `~` for the host's home.
 */
@Composable
internal fun TemplateDialog(initial: LaunchTemplate?, onDismiss: () -> Unit, onSave: (LaunchTemplate) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var directory by remember(initial) { mutableStateOf(initial?.directory.orEmpty()) }
    var command by remember(initial) { mutableStateOf(initial?.command.orEmpty()) }
    val id = remember(initial) { initial?.id ?: LaunchTemplate.newId() }
    val draft = LaunchTemplate(id, name.trim().take(LaunchTemplate.MAX_NAME), directory.trim(), command.trim())
    val ok = draft.usable
    fun save() {
        if (ok) onSave(draft)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(stringResource(if (initial == null) R.string.template_add_title else R.string.template_edit_title), color = Colors.ink) },
        text = {
            Column {
                Box(Modifier.testTag(TEMPLATE_NAME_TAG)) {
                    Field(stringResource(R.string.field_name), name, placeholder = stringResource(R.string.template_name_hint), imeAction = ImeAction.Next) { name = it }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.testTag(TEMPLATE_DIR_TAG)) {
                    Field(stringResource(R.string.template_directory), directory, placeholder = "~/project", autoCorrect = false, imeAction = ImeAction.Next) {
                        directory = it
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.testTag(TEMPLATE_COMMAND_TAG)) {
                    Field(
                        stringResource(R.string.template_command),
                        command,
                        placeholder = "claude",
                        autoCorrect = false,
                        imeAction = ImeAction.Done,
                        onDone = { save() },
                    ) { command = it }
                }
                Text(stringResource(R.string.template_help), color = Colors.muted, fontSize = TextStyles.label, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = ok) { Text(stringResource(R.string.action_save), color = if (ok) Colors.accent else Colors.muted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) } },
    )
}
