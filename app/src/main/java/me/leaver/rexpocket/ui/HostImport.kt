package me.leaver.rexpocket.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ExportedHost
import me.leaver.rexpocket.core.GeneratedKey
import me.leaver.rexpocket.core.HostAddressParser
import me.leaver.rexpocket.core.HostImportParse
import me.leaver.rexpocket.core.HostImportPlan
import me.leaver.rexpocket.core.HostListExport
import me.leaver.rexpocket.core.HostListFormat
import me.leaver.rexpocket.core.ImportAddress
import me.leaver.rexpocket.core.ImportField
import me.leaver.rexpocket.core.ImportPlan
import me.leaver.rexpocket.core.ImportProblem
import me.leaver.rexpocket.core.ImportStatus
import me.leaver.rexpocket.core.KeyChange
import me.leaver.rexpocket.core.KnownHost
import me.leaver.rexpocket.core.PlannedHost
import me.leaver.rexpocket.core.PublicKeyList
import me.leaver.rexpocket.core.SshKeys
import me.leaver.rexpocket.core.applyTo
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.diagnostics.Diagnostics

/** Where the hosts being imported came from. */
sealed interface ImportFrom {
    data object File : ImportFrom
    data class Rex(val hostLabel: String) : ImportFrom
}

/** The import sheet's page: where from, what it would change, and the keys to install after. */
sealed interface HostImportStep {
    /** Where to import from; [problem] is why the last list could not be imported. */
    data class Source(
        val problem: ImportProblem? = null,
        val failure: String? = null,
        val reading: Boolean = false,
        val exported: HostListExport? = null,
    ) : HostImportStep

    data class Preview(val plan: ImportPlan, val from: ImportFrom, val confirmed: Set<String> = emptySet()) : HostImportStep

    /** The public keys of the hosts that got one, and the hosts whose key the phone could not keep. */
    data class Keys(val entries: List<PublicKeyList.Entry>, val unsaved: List<String>, val created: Int, val changed: Int) : HostImportStep
}

/**
 * Import hosts: a `rexpocket-hosts/1` list from a file, the clipboard or another app, or the connected
 * Rex's host list, through one pipeline - read and check it all, show the plan, apply on one confirm,
 * then the new hosts' public keys. Nothing changes before the confirm; nothing is removed; a pinned
 * host key is replaced only for a host confirmed on its own. See docs/design/host-import.md.
 */
class HostImporter(
    private val app: Application,
    private val store: HostStore,
    private val scope: CoroutineScope,
    /** Called once the store changed, so the home shows the hosts. */
    private val onChanged: () -> Unit,
    /** Reads the host list of the Rex behind this saved host's connection; null when not connected. */
    private val rexHosts: suspend (hostId: String) -> HostImportParse?,
) {
    private val _step = MutableStateFlow<HostImportStep?>(null)
    val step: StateFlow<HostImportStep?> = _step

    fun open() {
        _step.value = HostImportStep.Source()
    }

    fun close() {
        _step.value = null
    }

    fun back() {
        _step.value = HostImportStep.Source()
    }

    /** The new keys' page, for hosts another import made: a settings backup's. */
    fun showKeys(keys: HostImportStep.Keys) {
        _step.value = keys
    }

    /** A list as text: pasted, or shared from another app. */
    fun readText(text: String) {
        plan(HostListFormat.parse(text), ImportFrom.File)
    }

    /**
     * A list in a file: picked, opened with the app, or shared. With [otherwise], a file that does not
     * look like a host list is handed on there instead (a JSON file shared to send to a pane).
     */
    fun readUri(uri: Uri, otherwise: (() -> Unit)? = null) {
        if (otherwise == null) _step.value = HostImportStep.Source(reading = true)
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (out.size() <= HostListFormat.MAX_BYTES) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            val head = bytes?.let { String(it, 0, minOf(it.size, HostListFormat.MAX_BYTES), Charsets.UTF_8) }
            if (otherwise != null && (head == null || !HostListFormat.mentionsFormat(head))) {
                otherwise()
                return@launch
            }
            if (bytes == null) {
                _step.value = HostImportStep.Source(problem = ImportProblem(ImportProblem.Kind.NotJson))
                return@launch
            }
            plan(HostListFormat.parse(bytes), ImportFrom.File)
        }
    }

    /**
     * A share from another app: a host list goes to the import, anything else to [otherwise] (the
     * share sheet). True when it was taken here or is being looked at.
     */
    fun offerShare(request: ShareRequest, type: String?, otherwise: (ShareRequest) -> Unit): Boolean {
        val text = request.text
        if (request.files.isEmpty() && text != null && HostListFormat.mentionsFormat(text)) {
            readText(text)
            return true
        }
        val file = request.files.singleOrNull() ?: return false
        val json = type == "application/json" || file.name.endsWith(".json", ignoreCase = true)
        if (!json && type?.startsWith("text/") != true) return false
        readUri(file.uri) { otherwise(request) }
        return true
    }

    /** The host list of the Rex behind [host], which must be connected. */
    fun readRex(host: SavedHost) {
        _step.value = HostImportStep.Source(reading = true)
        scope.launch {
            val read = runCatching { rexHosts(host.id) }
            val parse = read.getOrNull()
            if (parse == null) {
                _step.value = HostImportStep.Source(failure = read.exceptionOrNull()?.message ?: "not connected")
                return@launch
            }
            plan(parse, ImportFrom.Rex(host.label))
        }
    }

    private fun plan(parse: HostImportParse, from: ImportFrom) {
        when (parse) {
            is HostImportParse.Failed -> {
                Diagnostics.event(TAG, "import refused: ${parse.problem.kind} at entry ${parse.problem.entry ?: "-"}")
                _step.value = HostImportStep.Source(problem = parse.problem)
            }
            is HostImportParse.Parsed -> when (val planned = ImportPlan.of(parse, store.list().map { it.known() })) {
                is HostImportPlan.Failed -> _step.value = HostImportStep.Source(problem = planned.problem)
                is HostImportPlan.Planned -> {
                    Diagnostics.event(TAG, "import read: ${parse.hosts.size} hosts, ${parse.skipped.size} skipped")
                    _step.value = HostImportStep.Preview(planned.plan, from)
                }
            }
        }
    }

    /** The per-host confirmation a key change needs. */
    fun confirmKeyChange(name: String, on: Boolean) {
        val preview = _step.value as? HostImportStep.Preview ?: return
        _step.value = preview.copy(confirmed = if (on) preview.confirmed + name else preview.confirmed - name)
    }

    /** The one confirm: writes the plan, with a fresh key for each host that needs one. */
    fun apply() {
        val preview = _step.value as? HostImportStep.Preview ?: return
        val result = preview.plan.confirm(preview.confirmed)
        _step.value = HostImportStep.Source(reading = true)
        scope.launch {
            val keyed = (result.creates + result.updates).filter { it.needsKey }
            val keys: Map<String, GeneratedKey> = withContext(Dispatchers.Default) {
                keyed.associate { it.imported.name to SshKeys.generate(it.imported.commentForKey) }
            }
            val all = result.applyTo(
                store.list(),
                id = { it.id },
                update = { h, p -> h.imported(p, keys[p.imported.name]) },
                create = { p ->
                    SavedHost(id = HostStore.newId(), label = p.imported.name, host = "", port = 0, plaintext = true)
                        .imported(p, keys[p.imported.name])
                },
            )
            store.saveAll(all)
            onChanged()
            Diagnostics.event(TAG, result.summary())
            // A phone whose keystore cannot seal a key keeps none: say so rather than show a key that will not work.
            val saved = store.list().associateBy { it.label }
            val unsaved = keys.keys.filter { saved[it]?.sshPrivateKey.isNullOrEmpty() }
            val entries = keyed.mapNotNull { p -> keys[p.imported.name]?.let { PublicKeyList.Entry(p.imported.name, it.publicKey) } }
            _step.value = if (entries.isEmpty()) {
                null
            } else {
                HostImportStep.Keys(entries, unsaved, result.creates.size, result.updates.size)
            }
            if (entries.isEmpty()) toast(app, app.getString(R.string.import_done, result.creates.size, result.updates.size))
        }
    }

    /** Export hosts: the phone's SSH hosts as `rexpocket-hosts/1`, public data only. */
    fun export(): HostListExport = HostListFormat.write(
        store.list().filter { it.viaSsh }.map { h ->
            ExportedHost(
                name = h.label,
                address = h.importAddress(),
                fallbacks = h.fallbacks.mapNotNull(ImportAddress::parse),
                fingerprint = h.sshHostKey.ifBlank { null },
                keyComment = h.sshPrivateKey.takeIf { it.isNotBlank() }?.let(SshKeys::commentOf),
            )
        },
    ).also {
        Diagnostics.event(TAG, "export: ${it.written.size} hosts, ${it.left.size} left out")
        _step.value = HostImportStep.Source(exported = it)
    }

    /** Writes [text] to a file the person picked. */
    fun saveExport(uri: Uri, text: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
            }
            if (!ok) toast(app, app.getString(R.string.export_failed))
        }
    }

    companion object {
        const val TAG = "RexPocketImport"
    }
}

internal fun SavedHost.importAddress(): ImportAddress =
    if (viaSsh) ImportAddress(sshUser, host, port, plaintext = true) else ImportAddress(null, host, port, plaintext)

internal fun SavedHost.known(): KnownHost = KnownHost(
    id = id,
    name = label,
    address = importAddress(),
    fallbacks = fallbacks.mapNotNull(ImportAddress::parse),
    hostKey = sshHostKey.ifBlank { null },
    hasCredentials = !viaSsh || sshPassword.isNotEmpty() || sshPrivateKey.isNotBlank(),
)

/**
 * This host as the import leaves it: the entry's name and addresses, the pinned key the plan says,
 * and [key] (a fresh one of its own) when it gets one. Its password or key stays otherwise.
 */
internal fun SavedHost.imported(p: PlannedHost, key: GeneratedKey?): SavedHost {
    val a = p.imported.address
    return copy(
        label = p.imported.name,
        host = a.host,
        port = a.port,
        plaintext = a.plaintext,
        viaTailscale = HostAddressParser.isTailnet(a.host),
        sshUser = a.user.orEmpty(),
        sshPassword = if (!a.ssh || key != null) "" else sshPassword,
        sshPrivateKey = key?.privateKey ?: if (a.ssh) sshPrivateKey else "",
        sshPassphrase = if (!a.ssh || key != null) "" else sshPassphrase,
        sshHostKey = if (a.ssh) p.pinnedKey(sshHostKey) else "",
        fallbacks = if (p.imported.fallbacks.isEmpty()) fallbacks else p.imported.fallbacks.map { it.toString() },
    )
}

/**
 * An intent that opens a host list: "Open with" RexPocket on a file (`ACTION_VIEW`). Only another
 * app's `content:` URI, as for a share.
 */
internal fun hostListToOpen(context: Context, intent: Intent): Uri? {
    if (intent.action != Intent.ACTION_VIEW) return null
    val uri = intent.data ?: return null
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority?.startsWith(context.packageName) == true) return null
    return uri
}

internal const val HOST_IMPORT_TAG = "host-import"

/** The import sheet, on whichever page [step] is. [connected] are the hosts whose Rex can be asked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HostImportSheet(step: HostImportStep, connected: List<SavedHost>, importer: HostImporter) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importer.readUri(it) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = (importer.step.value as? HostImportStep.Source)?.exported?.text
        if (uri != null && text != null) importer.saveExport(uri, text)
    }
    when (step) {
        is HostImportStep.Source -> ActionSheet(onDismiss = importer::close, title = stringResource(R.string.import_hosts_title)) {
            Column(Modifier.padding(horizontal = 16.dp).testTag(HOST_IMPORT_TAG)) {
                if (step.reading) Text(stringResource(R.string.import_reading), color = Colors.muted, fontSize = TextStyles.label)
                step.problem?.let { Text(stringResource(R.string.import_refused) + " " + importProblemText(it), color = Colors.danger, fontSize = TextStyles.body, lineHeight = labelLeading) }
                step.failure?.let { Text(stringResource(R.string.import_rex_failed, it), color = Colors.danger, fontSize = TextStyles.body) }
            }
            Group(stringResource(R.string.import_group_file)) {
                SheetItem(stringResource(R.string.import_from_file), icon = R.drawable.ic_attach_file, keepOpen = true) {
                    pick.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                }
                SheetItem(stringResource(R.string.import_from_clipboard), icon = R.drawable.ic_paste, keepOpen = true) {
                    val text = clipboard(context)
                    if (text.isNullOrBlank()) toast(context, context.getString(R.string.import_clipboard_empty)) else importer.readText(text)
                }
            }
            Group(stringResource(R.string.import_group_rex)) {
                if (connected.isEmpty()) {
                    SheetItem(stringResource(R.string.import_rex_not_connected), enabled = false) {}
                }
                connected.forEach { host ->
                    SheetItem(stringResource(R.string.import_from_rex, host.label), icon = R.drawable.ic_host, keepOpen = true) { importer.readRex(host) }
                }
            }
            Group(stringResource(R.string.import_group_export)) {
                SheetItem(stringResource(R.string.export_hosts_file), icon = R.drawable.ic_attach_file, keepOpen = true) {
                    importer.export()
                    save.launch("rexpocket-hosts.json")
                }
                SheetItem(stringResource(R.string.export_hosts_share), icon = R.drawable.ic_share, keepOpen = true) {
                    shareText(context, importer.export().text)
                }
            }
            step.exported?.let { e ->
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.export_written, e.written.size), color = Colors.muted, fontSize = TextStyles.label)
                    if (e.left.isNotEmpty()) Text(stringResource(R.string.export_left, e.left.joinToString(", ")), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
                }
            }
        }
        is HostImportStep.Preview -> ActionSheet(onDismiss = importer::close, title = stringResource(R.string.import_preview_title), onBack = importer::back) {
            val result = step.plan.confirm(step.confirmed)
            val counts = step.plan.hosts.groupingBy { it.status }.eachCount()
            Column(Modifier.padding(horizontal = 16.dp).testTag(HOST_IMPORT_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (val from = step.from) {
                        ImportFrom.File -> stringResource(R.string.import_from_a_file)
                        is ImportFrom.Rex -> stringResource(R.string.import_from_rex, from.hostLabel)
                    },
                    color = Colors.muted,
                    fontSize = TextStyles.label,
                )
                Text(
                    stringResource(R.string.import_counts, counts[ImportStatus.New] ?: 0, counts[ImportStatus.Changed] ?: 0, counts[ImportStatus.Unchanged] ?: 0),
                    color = Colors.ink,
                    fontSize = TextStyles.body,
                )
                step.plan.skipped.forEach { Text(stringResource(R.string.import_skipped, it.name), color = Colors.amber, fontSize = TextStyles.label) }
            }
            step.plan.hosts.forEach { host ->
                Group {
                    PlannedRow(host, confirmed = host.imported.name in step.confirmed, held = host in result.held) { on ->
                        importer.confirmKeyChange(host.imported.name, on)
                    }
                }
            }
            val any = result.creates.isNotEmpty() || result.updates.isNotEmpty()
            Group {
                SheetItem(
                    stringResource(if (any) R.string.import_apply else R.string.import_nothing),
                    if (any) Colors.accent else Colors.muted,
                    icon = R.drawable.ic_check,
                    keepOpen = true,
                    enabled = any,
                    onClick = importer::apply,
                )
            }
        }
        is HostImportStep.Keys -> ActionSheet(onDismiss = importer::close, title = stringResource(R.string.keys_title)) {
            Column(Modifier.padding(horizontal = 16.dp).testTag(HOST_IMPORT_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.import_done, step.created, step.changed), color = Colors.ink, fontSize = TextStyles.body)
                Text(stringResource(R.string.keys_body), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
                if (step.unsaved.isNotEmpty()) {
                    Text(stringResource(R.string.keys_unsaved, step.unsaved.joinToString(", ")), color = Colors.danger, fontSize = TextStyles.label)
                }
            }
            step.entries.forEach { entry ->
                Group {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(entry.name, color = Colors.ink, fontSize = TextStyles.body, fontWeight = FontWeight.Medium)
                        SelectionContainer {
                            Text(entry.publicKey, color = Colors.muted, fontSize = TextStyles.label, fontFamily = FontFamily.Monospace)
                        }
                        FlowRow {
                            TextButton(onClick = { clipboardSet(context, entry.publicKey); toast(context, context.getString(R.string.keys_copied)) }) {
                                Text(stringResource(R.string.keys_copy), color = Colors.accent)
                            }
                            TextButton(onClick = { shareText(context, entry.publicKey) }) { Text(stringResource(R.string.keys_share), color = Colors.accent) }
                        }
                    }
                }
            }
            Group {
                val text = PublicKeyList.authorizedKeys(step.entries)
                val json = PublicKeyList.json(step.entries)
                SheetItem(stringResource(R.string.keys_copy_all), icon = R.drawable.ic_copy, keepOpen = true) {
                    clipboardSet(context, text)
                    toast(context, context.getString(R.string.keys_copied))
                }
                SheetItem(stringResource(R.string.keys_share_all), icon = R.drawable.ic_share, keepOpen = true) { shareText(context, text) }
                SheetItem(stringResource(R.string.keys_copy_json), icon = R.drawable.ic_copy, keepOpen = true) {
                    clipboardSet(context, json)
                    toast(context, context.getString(R.string.keys_copied))
                }
                SheetItem(stringResource(R.string.keys_share_json), icon = R.drawable.ic_share, keepOpen = true) { shareText(context, json) }
            }
            Group {
                SheetItem(stringResource(R.string.keys_done), Colors.accent, icon = R.drawable.ic_check, onClick = importer::close)
            }
        }
    }
}

/** One host of the plan: its name and status, what changes, its host key, and the key it gets. */
@Composable
internal fun PlannedRow(host: PlannedHost, confirmed: Boolean, held: Boolean, credentialsFromBackup: Boolean = false, onConfirm: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(host.imported.name, color = Colors.ink, fontSize = TextStyles.body, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            val (status, color) = when (host.status) {
                ImportStatus.New -> stringResource(R.string.import_status_new) to Colors.ok
                ImportStatus.Changed -> stringResource(R.string.import_status_changed) to Colors.amber
                ImportStatus.Unchanged -> stringResource(R.string.import_status_unchanged) to Colors.muted
            }
            Text(status, color = color, fontSize = TextStyles.label, fontWeight = FontWeight.Medium)
        }
        if (host.existing == null) {
            Text(host.imported.address.toString(), color = Colors.muted, fontSize = TextStyles.label)
            if (host.imported.fallbacks.isNotEmpty()) {
                Text(fieldName(ImportField.Fallbacks) + ": " + host.imported.fallbacks.joinToString(", "), color = Colors.muted, fontSize = TextStyles.label)
            }
        }
        host.changes.forEach { c ->
            Text(
                stringResource(R.string.import_change, fieldName(c.field), c.old ?: stringResource(R.string.import_none), c.new ?: stringResource(R.string.import_none)),
                color = Colors.ink,
                fontSize = TextStyles.label,
                lineHeight = labelLeading,
            )
        }
        when (val k = host.key) {
            KeyChange.Kept -> Text(
                if (host.existing?.hostKey != null) stringResource(R.string.import_key_kept) else stringResource(R.string.import_key_tofu),
                color = Colors.muted,
                fontSize = TextStyles.label,
                lineHeight = labelLeading,
            )
            KeyChange.Same -> Text(stringResource(R.string.import_key_same), color = Colors.muted, fontSize = TextStyles.label)
            is KeyChange.Set -> KeysSideBySide(null, k.new, Colors.ink)
            is KeyChange.Replaced -> {
                Text(stringResource(R.string.import_key_conflict), color = Colors.danger, fontSize = TextStyles.label, lineHeight = labelLeading)
                KeysSideBySide(k.old, k.new, Colors.danger)
                Row(
                    Modifier.fillMaxWidth().toggleable(value = confirmed, role = Role.Checkbox, onValueChange = onConfirm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = confirmed, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Colors.danger))
                    Text(stringResource(R.string.import_key_confirm, host.imported.name), color = Colors.danger, fontSize = TextStyles.label, modifier = Modifier.padding(start = 8.dp))
                }
                if (held) Text(stringResource(R.string.import_key_held), color = Colors.muted, fontSize = TextStyles.label)
            }
        }
        if (credentialsFromBackup) {
            Text(stringResource(R.string.backup_host_signs_in), color = Colors.muted, fontSize = TextStyles.label)
        } else if (host.needsKey) {
            Text(stringResource(R.string.import_new_key, host.imported.commentForKey), color = Colors.muted, fontSize = TextStyles.label)
        }
    }
}

/** The pinned host key and the import's, side by side, for the eye to compare. */
@Composable
private fun KeysSideBySide(old: String?, new: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.import_key_old), color = Colors.muted, fontSize = TextStyles.label)
            Text(old ?: stringResource(R.string.import_none), color = Colors.ink, fontSize = TextStyles.label, fontFamily = FontFamily.Monospace)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.import_key_new), color = Colors.muted, fontSize = TextStyles.label)
            Text(new, color = color, fontSize = TextStyles.label, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun fieldName(field: ImportField): String = stringResource(
    when (field) {
        ImportField.Name -> R.string.import_field_name
        ImportField.Address -> R.string.import_field_address
        ImportField.Fallbacks -> R.string.import_field_fallbacks
    },
)

/** Why a list cannot be imported, naming the entry when it is one entry's fault. */
@Composable
internal fun importProblemText(p: ImportProblem): String {
    val field = p.field ?: "uri"
    val why = when (p.kind) {
        ImportProblem.Kind.TooLarge -> stringResource(R.string.import_problem_too_large)
        ImportProblem.Kind.NotJson -> stringResource(R.string.import_problem_not_json)
        ImportProblem.Kind.MissingFormat -> stringResource(R.string.import_problem_missing_format, HostListFormat.FORMAT)
        ImportProblem.Kind.WrongFormat -> stringResource(R.string.import_problem_wrong_format, p.detail.orEmpty(), HostListFormat.FORMAT)
        ImportProblem.Kind.NoHosts -> stringResource(R.string.import_problem_no_hosts)
        ImportProblem.Kind.TooManyHosts -> stringResource(R.string.import_problem_too_many, p.detail.orEmpty(), HostListFormat.MAX_HOSTS)
        ImportProblem.Kind.NotAnEntry -> stringResource(R.string.import_problem_not_entry)
        ImportProblem.Kind.BadName -> stringResource(R.string.import_problem_bad_name)
        ImportProblem.Kind.DuplicateName -> stringResource(R.string.import_problem_duplicate)
        ImportProblem.Kind.NoAddress -> stringResource(R.string.import_problem_no_address)
        ImportProblem.Kind.BadUri -> stringResource(R.string.import_problem_bad_uri, field)
        ImportProblem.Kind.NotSsh -> stringResource(R.string.import_problem_not_ssh, field)
        ImportProblem.Kind.UriHasPassword -> stringResource(R.string.import_problem_password, field)
        ImportProblem.Kind.UriHasPath -> stringResource(R.string.import_problem_path, field)
        ImportProblem.Kind.UriHasQuery -> stringResource(R.string.import_problem_query, field)
        ImportProblem.Kind.UriNoUser -> stringResource(R.string.import_problem_no_user, field)
        ImportProblem.Kind.BadPort -> stringResource(R.string.import_problem_port, field)
        ImportProblem.Kind.BadFingerprint -> stringResource(R.string.import_problem_fingerprint)
        ImportProblem.Kind.BadKeyComment -> stringResource(R.string.import_problem_comment)
        ImportProblem.Kind.AmbiguousMatch -> stringResource(R.string.import_problem_ambiguous, p.detail.orEmpty())
    }
    val entry = p.entry ?: return why
    val name = p.name
    return if (name != null) stringResource(R.string.import_entry_named, entry, name, why) else stringResource(R.string.import_entry, entry, why)
}

/** Under Add host at the end of the list: many hosts at once, from a host list or a connected Rex. */
@Composable
internal fun ImportHostsRow(onClick: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Radius.card)).background(Colors.surface)
            .clickable(role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(R.drawable.ic_host, Colors.accent, size = 24.dp)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.import_hosts_title), color = Colors.accent, fontSize = TextStyles.subhead, fontWeight = FontWeight.Medium)
    }
}
