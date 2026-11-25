package me.leaver.rexpocket.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.AlertPrefs
import me.leaver.rexpocket.core.AppTheme
import me.leaver.rexpocket.core.Appearance
import me.leaver.rexpocket.core.BackgroundHosts
import me.leaver.rexpocket.core.BackupCrypto
import me.leaver.rexpocket.core.CurrentSettings
import me.leaver.rexpocket.core.ExportedHost
import me.leaver.rexpocket.core.GeneratedKey
import me.leaver.rexpocket.core.HomePrefs
import me.leaver.rexpocket.core.HostBackground
import me.leaver.rexpocket.core.HostImportParse
import me.leaver.rexpocket.core.HostImportPlan
import me.leaver.rexpocket.core.HostSecret
import me.leaver.rexpocket.core.ImportAddress
import me.leaver.rexpocket.core.ImportPlan
import me.leaver.rexpocket.core.ImportStatus
import me.leaver.rexpocket.core.OpenedSecrets
import me.leaver.rexpocket.core.PublicKeyList
import me.leaver.rexpocket.core.QuickReplies
import me.leaver.rexpocket.core.SettingChange
import me.leaver.rexpocket.core.SettingKey
import me.leaver.rexpocket.core.SettingsBackup
import me.leaver.rexpocket.core.SettingsExport
import me.leaver.rexpocket.core.SettingsFormat
import me.leaver.rexpocket.core.SettingsParse
import me.leaver.rexpocket.core.SettingsProblem
import me.leaver.rexpocket.core.SettingsSnapshot
import me.leaver.rexpocket.core.SshKeys
import me.leaver.rexpocket.core.TerminalColors
import me.leaver.rexpocket.core.TextSize
import me.leaver.rexpocket.core.WrongPassphraseException
import me.leaver.rexpocket.core.applyTo
import me.leaver.rexpocket.core.credentialsFor
import me.leaver.rexpocket.core.inFileOrder
import me.leaver.rexpocket.core.settingChanges
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.GestureSettings
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.KeyBarPrefs
import me.leaver.rexpocket.core.SecurityPrefs
import me.leaver.rexpocket.core.TailnetProfiles
import me.leaver.rexpocket.data.FontCatalog
import me.leaver.rexpocket.data.SYSTEM_FONT
import me.leaver.rexpocket.data.HomeFolds
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.diagnostics.Diagnostics

/** The phone's settings, as the view model holds them: what a backup is made of and laid onto. */
data class PhoneSettings(
    val appearance: Appearance,
    val fontScale: Float,
    val alertPrefs: AlertPrefs,
    val backgroundMode: Boolean,
    val filter: HomeFilter,
    val reopenLastTab: Boolean,
    val activity: ActivityPrefs = ActivityPrefs(),
    val folds: HomeFolds,
    val background: BackgroundHosts,
    val quickReplies: List<String>,
    /** Each host's launch templates, by host id. */
    val templates: Map<String, List<me.leaver.rexpocket.core.LaunchTemplate>> = emptyMap(),
    val terminalFont: String = SYSTEM_FONT,
    val keyBarAlways: Boolean = false,
    val ctrlC: CtrlCKey = CtrlCKey.Tap,
    val keyArrangement: KeyArrangement = KeyArrangement(),
    val gestures: GestureSettings = GestureSettings(),
    val security: SecurityPrefs = SecurityPrefs(),
    /** The tailnet accounts: a host's account goes in by name and comes back by name. */
    val accounts: TailnetProfiles = TailnetProfiles(),
)

/** What an import writes beside the hosts; null leaves a setting as it is. Hosts by id. */
data class SettingsWrite(
    val appearance: Appearance? = null,
    val fontScale: Float? = null,
    val alertPrefs: AlertPrefs? = null,
    val backgroundMode: Boolean? = null,
    val filter: HomeFilter? = null,
    val reopenLastTab: Boolean? = null,
    val activityBySession: Boolean? = null,
    val activityIncludeIdle: Boolean? = null,
    val folds: HomeFolds? = null,
    val background: Map<String, HostBackground> = emptyMap(),
    val quickReplies: List<String>? = null,
    /** Launch templates, by host id: each host's replace that host's. */
    val templates: Map<String, List<me.leaver.rexpocket.core.LaunchTemplate>> = emptyMap(),
    /** A font of this version's list: applied, downloading it first when it is not on the phone. */
    val terminalFont: String? = null,
    val keyBarAlways: Boolean? = null,
    val ctrlC: CtrlCKey? = null,
    val keyArrangement: KeyArrangement? = null,
    val gestures: GestureSettings? = null,
    val security: SecurityPrefs? = null,
)

/** The sealed SSH credentials of the file being imported. */
sealed interface BackupSecrets {
    data object None : BackupSecrets

    /** Not opened yet; [wrong] after a passphrase that did not open them. */
    data class Locked(val hosts: Int, val wrong: Boolean = false, val opening: Boolean = false) : BackupSecrets
    data class Opened(val hosts: Map<String, HostSecret>) : BackupSecrets
}

sealed interface BackupStep {
    /** Export and import; [problem] is why the last file could not be imported. */
    data class Menu(
        val includeKeys: Boolean = false,
        val busy: Boolean = false,
        val problem: SettingsProblem? = null,
        val exported: SettingsExport? = null,
        /** A file built to share, waiting for the sheet to hand it to the share sheet. */
        val share: String? = null,
        val failed: Boolean = false,
    ) : BackupStep

    data class Preview(
        val backup: SettingsBackup,
        val plan: ImportPlan,
        val changes: List<SettingChange>,
        val confirmed: Set<String> = emptySet(),
        val secrets: BackupSecrets = BackupSecrets.None,
    ) : BackupStep
}

/**
 * Settings › Backup: every setting and host to one `rexpocket-settings/1` file, and such a file
 * back onto the phone - read and checked whole, shown as what changes, applied on one confirm. Its
 * hosts take Import hosts' plan and trust rules; nothing is ever removed. See
 * docs/design/settings-backup.md.
 */
class SettingsBackupper(
    private val app: Application,
    private val store: HostStore,
    private val scope: CoroutineScope,
    private val read: () -> PhoneSettings,
    /** Writes the settings; returns false when background mode could not be turned on (no permission). */
    private val write: (SettingsWrite) -> Boolean,
    private val onHostsChanged: () -> Unit,
    /** Opens the new keys' page of Import hosts. */
    private val showKeys: (HostImportStep.Keys) -> Unit,
    /** The background mode switch, read now: it lives outside the store. */
    private val backgroundMode: () -> Boolean,
) {
    private val _step = MutableStateFlow<BackupStep?>(null)
    val step: StateFlow<BackupStep?> = _step

    fun open() {
        _step.value = BackupStep.Menu()
    }

    fun close() {
        _step.value = null
    }

    fun back() {
        _step.value = BackupStep.Menu()
    }

    fun includeKeys(on: Boolean) {
        val menu = _step.value as? BackupStep.Menu ?: BackupStep.Menu()
        _step.value = menu.copy(includeKeys = on)
    }

    /** What the phone has, as a backup writes it. */
    internal fun snapshot(withSecrets: Boolean): SettingsSnapshot {
        val now = read()
        val hosts = store.listWithSecrets()
        // A file names its hosts, and keeps only the first of two with one name: the parts by host
        // are that first one's, never another's under its name.
        val label = hosts.distinctBy { it.label.lowercase() }.associate { it.id to it.label }
        return SettingsSnapshot(
            appearance = now.appearance,
            terminalFontScale = now.fontScale,
            terminalFont = now.terminalFont,
            keyBar = KeyBarPrefs(always = now.keyBarAlways, ctrlC = now.ctrlC, arrangement = now.keyArrangement),
            gestures = now.gestures,
            security = now.security,
            notifications = now.alertPrefs,
            backgroundMode = backgroundMode(),
            home = HomePrefs(
                filter = now.filter.name,
                reopenLastTab = now.reopenLastTab,
                activityBySession = now.activity.bySession,
                includeIdle = now.activity.includeIdle,
                hostOrder = hosts.map { it.label },
                folded = now.folds.hosts.mapNotNull { label[it] }.toSet(),
                unfolded = now.folds.openHosts.mapNotNull { label[it] }.toSet(),
            ),
            hosts = hosts.map { it.exported(now.accounts) },
            hostBackground = now.background.hosts.mapNotNull { (id, b) -> label[id]?.let { it to b } }.toMap(),
            quickReplies = now.quickReplies,
            tailnets = tailnetChoicesOf(Connections.of(app)),
            tiles = tileBindingsOf(app, hosts.filter { it.id in label }),
            templates = now.templates.mapNotNull { (id, list) -> label[id]?.let { it to list } }.toMap(),
            secrets = if (!withSecrets) emptyList() else hosts.filter { it.viaSsh }.map { h ->
                HostSecret(h.label, h.sshPassword.ifEmpty { null }, h.sshPrivateKey.ifBlank { null }, h.sshPassphrase.ifEmpty { null })
            },
        )
    }

    private suspend fun build(passphrase: String?): SettingsExport? {
        val menu = _step.value as? BackupStep.Menu ?: BackupStep.Menu()
        _step.value = menu.copy(busy = true, exported = null, failed = false, problem = null)
        val export = withContext(Dispatchers.Default) {
            runCatching { SettingsFormat.write(snapshot(passphrase != null), passphrase) }.getOrNull()
        }
        if (export == null) {
            _step.value = menu.copy(busy = false, failed = true)
            return null
        }
        // Counts only: never a host's credentials, never the passphrase.
        Diagnostics.event(TAG, "export: ${export.hosts.size} hosts, ${export.left.size} left out, ${export.sealedHosts} sealed")
        return export
    }

    /** Writes the backup to a file the person picked; [passphrase] seals the SSH credentials into it. */
    fun saveTo(uri: Uri, passphrase: String?) {
        scope.launch {
            val export = build(passphrase) ?: return@launch
            val ok = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(export.text.toByteArray()) } != null }.getOrDefault(false)
            }
            val menu = _step.value as? BackupStep.Menu ?: BackupStep.Menu()
            _step.value = menu.copy(busy = false, exported = export.takeIf { ok }, failed = !ok)
        }
    }

    /** Builds the backup for the share sheet; the sheet hands it on ([shared]). */
    fun share(passphrase: String?) {
        scope.launch {
            val export = build(passphrase) ?: return@launch
            val menu = _step.value as? BackupStep.Menu ?: BackupStep.Menu()
            _step.value = menu.copy(busy = false, exported = export, share = export.text)
        }
    }

    fun shared() {
        val menu = _step.value as? BackupStep.Menu ?: return
        _step.value = menu.copy(share = null)
    }

    fun readText(text: String) = plan(SettingsFormat.parse(text))

    /**
     * A backup in a file: picked, opened with the app, or shared. With [otherwise], a file that is
     * not a settings backup goes there instead (a host list, or a JSON file for a pane).
     */
    fun readUri(uri: Uri, otherwise: (() -> Unit)? = null) {
        if (otherwise == null) _step.value = BackupStep.Menu(busy = true)
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        // One byte past the cap is enough to refuse it as too large.
                        while (out.size() <= SettingsFormat.MAX_BYTES) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            val head = bytes?.let { String(it, 0, minOf(it.size, 64 * 1024), Charsets.UTF_8) }
            if (otherwise != null && (head == null || !SettingsFormat.mentionsFormat(head))) {
                otherwise()
                return@launch
            }
            if (bytes == null) {
                _step.value = BackupStep.Menu(problem = SettingsProblem(SettingsProblem.Kind.NotJson))
                return@launch
            }
            plan(SettingsFormat.parse(bytes))
        }
    }

    /** A share from another app: a settings backup comes here; true when it was taken or is being looked at. */
    fun offerShare(request: ShareRequest, type: String?, otherwise: () -> Unit): Boolean {
        val text = request.text
        if (request.files.isEmpty() && text != null && SettingsFormat.mentionsFormat(text)) {
            readText(text)
            return true
        }
        val file = request.files.singleOrNull() ?: return false
        val json = type == "application/json" || file.name.endsWith(".json", ignoreCase = true)
        if (!json && type?.startsWith("text/") != true) return false
        readUri(file.uri, otherwise)
        return true
    }

    private fun plan(parse: SettingsParse) {
        when (parse) {
            is SettingsParse.Failed -> {
                Diagnostics.event(TAG, "import refused: ${parse.problem.kind} ${parse.problem.field ?: ""}")
                _step.value = BackupStep.Menu(problem = parse.problem)
            }
            is SettingsParse.Parsed -> {
                // A font this version's list does not have is a newer app's: left as the phone has it.
                val backup = parse.backup.let { b ->
                    val font = b.terminalFont
                    if (font != null && font != SYSTEM_FONT && FontCatalog.find(font) == null) b.copy(terminalFont = null) else b
                }
                val hosts = store.listWithSecrets()
                val accounts = read().accounts
                when (val planned = ImportPlan.of(HostImportParse.Parsed(backup.hosts), hosts.map { it.known(accounts) })) {
                    is HostImportPlan.Failed -> _step.value = BackupStep.Menu(
                        problem = SettingsProblem(SettingsProblem.Kind.Plan, "hosts", hosts = planned.problem),
                    )
                    is HostImportPlan.Planned -> {
                        val changes = settingChanges(backup, current(hosts), planned.plan)
                        Diagnostics.event(TAG, "import read: ${backup.hosts.size} hosts, ${changes.size} settings, secrets=${backup.secrets != null}")
                        _step.value = BackupStep.Preview(
                            backup,
                            planned.plan,
                            changes,
                            secrets = backup.secrets?.let { BackupSecrets.Locked(it.hosts) } ?: BackupSecrets.None,
                        )
                    }
                }
            }
        }
    }

    private fun current(hosts: List<SavedHost>): CurrentSettings {
        val now = read()
        return CurrentSettings(
            appearance = now.appearance,
            terminalFontScale = now.fontScale,
            notifications = now.alertPrefs,
            backgroundMode = backgroundMode(),
            homeFilter = now.filter.name,
            reopenLastTab = now.reopenLastTab,
            activityBySession = now.activity.bySession,
            includeIdle = now.activity.includeIdle,
            hosts = hosts.map { it.known(now.accounts) },
            hostBackground = now.background.hosts,
            folded = now.folds.hosts,
            unfolded = now.folds.openHosts,
            quickReplies = now.quickReplies,
            tailnets = tailnetChoicesOf(Connections.of(app), all = true),
            tiles = tileBindingsOf(app, hosts),
            templates = now.templates,
            terminalFont = now.terminalFont,
            keyBarAlways = now.keyBarAlways,
            ctrlC = now.ctrlC,
            keyArrangement = now.keyArrangement,
            gestures = now.gestures,
            security = now.security,
        )
    }

    fun confirmKeyChange(name: String, on: Boolean) {
        val preview = _step.value as? BackupStep.Preview ?: return
        _step.value = preview.copy(confirmed = if (on) preview.confirmed + name else preview.confirmed - name)
    }

    /** Opens the file's sealed SSH credentials with [passphrase], off the main thread (PBKDF2 takes a moment). */
    fun unlock(passphrase: String) {
        val preview = _step.value as? BackupStep.Preview ?: return
        val sealed = preview.backup.secrets ?: return
        _step.value = preview.copy(secrets = BackupSecrets.Locked(sealed.hosts, opening = true))
        scope.launch {
            val opened = withContext(Dispatchers.Default) {
                try {
                    SettingsFormat.open(preview.backup, sealed, passphrase)
                } catch (e: WrongPassphraseException) {
                    null
                }
            }
            val now = _step.value as? BackupStep.Preview ?: return@launch
            _step.value = when (opened) {
                null -> now.copy(secrets = BackupSecrets.Locked(sealed.hosts, wrong = true))
                is OpenedSecrets.Opened -> now.copy(secrets = BackupSecrets.Opened(opened.hosts))
                // A box that opens and holds the wrong things is a bad file: nothing of it is imported.
                is OpenedSecrets.Failed -> BackupStep.Menu(problem = opened.problem)
            }
            Diagnostics.event(TAG, "keys ${if (opened is OpenedSecrets.Opened) "opened (${opened.hosts.size} hosts)" else "not opened"}")
        }
    }

    /** The one confirm: the hosts by Import hosts' rules, then every setting the preview showed. */
    fun apply() {
        val preview = _step.value as? BackupStep.Preview ?: return
        val result = preview.plan.confirm(preview.confirmed)
        val secrets = (preview.secrets as? BackupSecrets.Opened)?.hosts.orEmpty()
        val held = result.held.map { it.imported.name.lowercase() }.toSet()
        _step.value = BackupStep.Menu(busy = true)
        scope.launch {
            val keyed = (result.creates + result.updates).filter { it.needsKey && credentialsFor(it, secrets) == null }
            val keys: Map<String, GeneratedKey> = withContext(Dispatchers.Default) {
                keyed.associate { it.imported.name to SshKeys.generate(it.imported.commentForKey) }
            }
            val ids = HashMap<String, String>()
            result.unchanged.forEach { p -> p.existing?.let { ids[p.imported.name.lowercase()] = it.id } }
            result.updates.forEach { p -> ids[p.imported.name.lowercase()] = p.existing!!.id }
            // The tailnet accounts first: a host names its account, which a wiped phone has only
            // once the file's accounts are added.
            preview.backup.tailnets?.let { applyTailnetChoices(Connections.of(app), it) }
            val accounts = read().accounts
            var all = result.applyTo(
                store.listWithSecrets(),
                id = { it.id },
                update = { h, p -> h.imported(p, keys[p.imported.name], accounts).signedInWith(credentialsFor(p, secrets)) },
                create = { p ->
                    SavedHost(id = HostStore.newId(), label = p.imported.name, host = "", port = 0, plaintext = true)
                        .imported(p, keys[p.imported.name], accounts).signedInWith(credentialsFor(p, secrets))
                        .also { ids[p.imported.name.lowercase()] = it.id }
                },
            )
            // An unchanged host can still take the backup's sign-in, when it has none of its own.
            all = all.map { h ->
                val p = result.unchanged.firstOrNull { it.existing?.id == h.id } ?: return@map h
                h.signedInWith(credentialsFor(p, secrets))
            }
            preview.backup.home?.hostOrder?.let { order ->
                all = inFileOrder(all, { it.label }, order.filter { it.lowercase() !in held })
            }
            store.saveAll(all)
            onHostsChanged()

            val b = preview.backup
            val now = read()
            fun id(name: String) = ids[name.lowercase()]?.takeIf { name.lowercase() !in held }
            var folds: HomeFolds? = null
            b.home?.let { h ->
                var f = now.folds
                h.folded.orEmpty().forEach { n -> id(n)?.let { f = f.withHost(it, open = false) } }
                h.unfolded.orEmpty().forEach { n -> id(n)?.let { f = f.withHost(it, open = true) } }
                if (f != now.folds) folds = f
            }
            val backgroundOk = write(
                SettingsWrite(
                    appearance = b.appearance,
                    fontScale = b.terminalFontScale,
                    alertPrefs = b.notifications,
                    backgroundMode = b.backgroundMode,
                    filter = b.home?.filter?.let(HomeFilter::of),
                    reopenLastTab = b.home?.reopenLastTab,
                    activityBySession = b.home?.activityBySession,
                    activityIncludeIdle = b.home?.includeIdle,
                    folds = folds,
                    background = b.hostBackground.mapNotNull { (n, bg) ->
                        // A file from before muted tabs leaves the phone's as they are.
                        id(n)?.let { it to if (n in b.mutedKept) bg.copy(muted = now.background.of(it).muted) else bg }
                    }.toMap(),
                    quickReplies = b.quickReplies,
                    templates = b.templates.mapNotNull { (n, list) -> id(n)?.let { it to list } }.toMap(),
                    terminalFont = b.terminalFont,
                    keyBarAlways = b.keyBar?.always,
                    ctrlC = b.keyBar?.ctrlC,
                    keyArrangement = b.keyBar?.arrangement,
                    gestures = b.gestures,
                    security = b.security,
                ),
            )
            b.tiles?.let { tiles -> applyTiles(app, tiles, ::id) }
            Diagnostics.event(TAG, "import: " + result.summary() + ", ${preview.changes.size} settings, ${secrets.size} sign-ins")
            val saved = store.listWithSecrets().associateBy { it.label }
            val unsaved = keys.keys.filter { saved[it]?.sshPrivateKey.isNullOrEmpty() }
            val entries = keyed.mapNotNull { p -> keys[p.imported.name]?.let { PublicKeyList.Entry(p.imported.name, it.publicKey) } }
            _step.value = null
            if (!backgroundOk) toast(app, app.getString(R.string.backup_background_not_on))
            if (entries.isNotEmpty()) {
                showKeys(HostImportStep.Keys(entries, unsaved, result.creates.size, result.updates.size))
            } else {
                toast(app, app.getString(R.string.backup_imported))
            }
        }
    }

    companion object {
        const val TAG = "RexPocketBackup"
    }
}

/** This host signing in with [secret] from a backup; unchanged without one. */
private fun SavedHost.signedInWith(secret: HostSecret?): SavedHost = if (secret == null) this else copy(
    sshPassword = secret.password.orEmpty(),
    sshPrivateKey = secret.privateKey.orEmpty(),
    sshPassphrase = secret.passphrase.orEmpty(),
)

internal const val BACKUP_TAG = "settings-backup"

/** Settings › Backup, on whichever page [step] is. */
@Composable
internal fun SettingsBackupSheet(
    step: BackupStep,
    backupper: SettingsBackupper,
    /** The first page's back arrow, when Settings opened it: back to Settings. */
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // The passphrase waits here while the file picker is open, and only there: never saved.
    var sealWith by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<ExportTo?>(null) }
    var unlocking by rememberSaveable { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { backupper.readUri(it) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) backupper.saveTo(uri, sealWith)
        sealWith = null
    }
    fun export(to: ExportTo, passphrase: String?) {
        when (to) {
            ExportTo.File -> {
                sealWith = passphrase
                save.launch("rexpocket-settings.json")
            }
            ExportTo.Share -> backupper.share(passphrase)
        }
    }
    when (step) {
        is BackupStep.Menu -> {
            LaunchedEffect(step.share) {
                step.share?.let {
                    shareText(context, it)
                    backupper.shared()
                }
            }
            ActionSheet(onDismiss = backupper::close, title = stringResource(R.string.backup_title), onBack = onBack) {
                Column(Modifier.padding(horizontal = 16.dp).testTag(BACKUP_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.backup_body), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
                    if (step.busy) Text(stringResource(R.string.backup_working), color = Colors.muted, fontSize = TextStyles.label)
                    step.problem?.let {
                        Text(stringResource(R.string.backup_refused) + " " + settingsProblemText(it), color = Colors.danger, fontSize = TextStyles.body, lineHeight = labelLeading)
                    }
                    if (step.failed) Text(stringResource(R.string.backup_export_failed), color = Colors.danger, fontSize = TextStyles.body)
                    step.exported?.let { e ->
                        Text(
                            if (e.sealedHosts > 0) stringResource(R.string.backup_written_keys, e.hosts.size, e.sealedHosts)
                            else stringResource(R.string.backup_written, e.hosts.size),
                            color = Colors.ok,
                            fontSize = TextStyles.label,
                        )
                        LeftOut(e.left, e.reasons)
                    }
                }
                Group(stringResource(R.string.backup_group_export)) {
                    SheetItem(
                        label = stringResource(R.string.backup_include_keys),
                        icon = R.drawable.ic_lock,
                        supporting = stringResource(R.string.backup_include_keys_body),
                        detail = stringResource(if (step.includeKeys) R.string.alerts_on else R.string.alerts_off),
                        checked = step.includeKeys,
                        keepOpen = true,
                        enabled = !step.busy,
                    ) { backupper.includeKeys(!step.includeKeys) }
                    SheetItem(stringResource(R.string.backup_save_file), icon = R.drawable.ic_attach_file, keepOpen = true, enabled = !step.busy) {
                        if (step.includeKeys) asking = ExportTo.File else export(ExportTo.File, null)
                    }
                    SheetItem(stringResource(R.string.backup_share), icon = R.drawable.ic_share, keepOpen = true, enabled = !step.busy) {
                        if (step.includeKeys) asking = ExportTo.Share else export(ExportTo.Share, null)
                    }
                }
                Group(stringResource(R.string.backup_group_import)) {
                    SheetItem(stringResource(R.string.import_from_file), icon = R.drawable.ic_attach_file, keepOpen = true, enabled = !step.busy) {
                        pick.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                    }
                    SheetItem(stringResource(R.string.import_from_clipboard), icon = R.drawable.ic_paste, keepOpen = true, enabled = !step.busy) {
                        val text = clipboard(context)
                        if (text.isNullOrBlank()) toast(context, context.getString(R.string.import_clipboard_empty)) else backupper.readText(text)
                    }
                }
            }
        }
        is BackupStep.Preview -> ActionSheet(onDismiss = backupper::close, title = stringResource(R.string.backup_preview_title), onBack = backupper::back) {
            val result = step.plan.confirm(step.confirmed)
            val counts = step.plan.hosts.groupingBy { it.status }.eachCount()
            val secrets = (step.secrets as? BackupSecrets.Opened)?.hosts.orEmpty()
            val signIns = (result.creates + result.updates + result.unchanged).count { credentialsFor(it, secrets) != null }
            Column(Modifier.padding(horizontal = 16.dp).testTag(BACKUP_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.import_counts, counts[ImportStatus.New] ?: 0, counts[ImportStatus.Changed] ?: 0, counts[ImportStatus.Unchanged] ?: 0),
                    color = Colors.ink,
                    fontSize = TextStyles.body,
                )
                Text(stringResource(R.string.backup_settings_count, step.changes.size), color = Colors.ink, fontSize = TextStyles.body)
                Text(stringResource(R.string.backup_never_removes), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
            }
            if (step.changes.isNotEmpty()) {
                Group(stringResource(R.string.backup_group_settings), icons = false) {
                    step.changes.forEach { c -> ChangeRow(c) }
                }
            }
            when (val s = step.secrets) {
                BackupSecrets.None -> Unit
                is BackupSecrets.Locked -> Group(stringResource(R.string.backup_group_keys)) {
                    SheetItem(
                        label = stringResource(R.string.backup_unlock),
                        icon = R.drawable.ic_lock,
                        supporting = when {
                            s.opening -> stringResource(R.string.backup_unlocking)
                            s.wrong -> stringResource(R.string.backup_wrong_passphrase)
                            else -> stringResource(R.string.backup_locked, s.hosts)
                        },
                        color = if (s.wrong) Colors.danger else Colors.ink,
                        keepOpen = true,
                        opens = true,
                        enabled = !s.opening,
                    ) { unlocking = true }
                }
                is BackupSecrets.Opened -> Group(stringResource(R.string.backup_group_keys)) {
                    SheetItem(stringResource(R.string.backup_unlocked, signIns), icon = R.drawable.ic_check, color = Colors.ok, keepOpen = true, enabled = false) {}
                }
            }
            step.plan.hosts.forEach { host ->
                Group {
                    PlannedRow(
                        host,
                        confirmed = host.imported.name in step.confirmed,
                        held = host in result.held,
                        credentialsFromBackup = credentialsFor(host, secrets) != null,
                    ) { on -> backupper.confirmKeyChange(host.imported.name, on) }
                }
            }
            val any = result.creates.isNotEmpty() || result.updates.isNotEmpty() || step.changes.isNotEmpty() || signIns > 0
            Group {
                SheetItem(
                    stringResource(if (any) R.string.backup_apply else R.string.import_nothing),
                    if (any) Colors.accent else Colors.muted,
                    icon = R.drawable.ic_check,
                    keepOpen = true,
                    enabled = any,
                    onClick = backupper::apply,
                )
            }
        }
    }
    asking?.let { to ->
        PassphraseDialog(
            title = stringResource(R.string.backup_passphrase_title),
            body = stringResource(R.string.backup_passphrase_body, BackupCrypto.MIN_PASSPHRASE),
            confirm = true,
            onDismiss = { asking = null },
        ) { passphrase ->
            asking = null
            export(to, passphrase)
        }
    }
    if (unlocking && step is BackupStep.Preview) {
        PassphraseDialog(
            title = stringResource(R.string.backup_unlock),
            body = stringResource(R.string.backup_unlock_body),
            confirm = false,
            onDismiss = { unlocking = false },
        ) { passphrase ->
            unlocking = false
            backupper.unlock(passphrase)
        }
    }
}

private enum class ExportTo { File, Share }

/**
 * Asks for the backup's passphrase. For a new one ([confirm]) it is typed twice and is at least
 * [BackupCrypto.MIN_PASSPHRASE] characters; it is never stored, and there is no way back without it.
 */
@Composable
private fun PassphraseDialog(title: String, body: String, confirm: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val long = first.length >= BackupCrypto.MIN_PASSPHRASE
    val same = !confirm || first == second
    val ready = if (confirm) long && same else first.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(title, color = Colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(body, color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
                Field(
                    stringResource(R.string.backup_passphrase),
                    first,
                    autoFocus = true,
                    password = true,
                    imeAction = if (confirm) ImeAction.Next else ImeAction.Done,
                    onDone = { if (ready) onDone(first) },
                ) { first = it }
                if (confirm) {
                    Field(
                        stringResource(R.string.backup_passphrase_again),
                        second,
                        password = true,
                        imeAction = ImeAction.Done,
                        onDone = { if (ready) onDone(first) },
                    ) { second = it }
                    if (first.isNotEmpty() && !long) {
                        Text(stringResource(R.string.backup_passphrase_short, BackupCrypto.MIN_PASSPHRASE), color = Colors.amber, fontSize = TextStyles.label)
                    } else if (second.isNotEmpty() && !same) {
                        Text(stringResource(R.string.backup_passphrase_mismatch), color = Colors.amber, fontSize = TextStyles.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onDone(first) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (confirm) R.string.backup_seal else R.string.backup_unlock_action), color = if (ready) Colors.accent else Colors.muted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
        },
    )
}

/** One setting the import changes: its name, and old → new. */
@Composable
private fun ChangeRow(c: SettingChange) {
    val name = when (c.key) {
        SettingKey.HostBackground -> stringResource(R.string.backup_setting_host_background, c.host.orEmpty())
        SettingKey.TailnetChoices -> stringResource(R.string.backup_setting_tailnet, c.host.orEmpty())
        SettingKey.Tile -> stringResource(R.string.backup_setting_tile, c.host.orEmpty())
        SettingKey.Templates -> stringResource(R.string.template_manage_title, c.host.orEmpty())
        else -> settingName(c.key)
    }
    val text = when (c.key) {
        SettingKey.TailnetChoices, SettingKey.Tile -> tailnetChangeText(c)
        SettingKey.HostFolds -> stringResource(R.string.backup_setting_folds_value, (c.new as? List<*>).orEmpty().joinToString(", "))
        SettingKey.HostOrder -> stringResource(R.string.import_change_short, (c.old as? List<*>).orEmpty().joinToString(", "), (c.new as? List<*>).orEmpty().joinToString(", "))
        SettingKey.Templates -> stringResource(
            R.string.import_change_short,
            (c.old as? List<*>).orEmpty().joinToString(", ").ifEmpty { stringResource(R.string.import_none) },
            (c.new as? List<*>).orEmpty().joinToString(", ").ifEmpty { stringResource(R.string.import_none) },
        )
        // The keys by their faces would be a long line: the change is named, Arrange keys shows it.
        SettingKey.KeyArrangement -> stringResource(R.string.backup_setting_key_arrangement_value)
        SettingKey.Gestures -> stringResource(R.string.backup_setting_gestures_value)
        SettingKey.TerminalFont -> stringResource(
            R.string.import_change_short,
            fontName(c.old as? String),
            fontName(c.new as? String) + (FontCatalog.find(c.new as? String ?: "")?.let { " " + stringResource(R.string.backup_font_downloads, it.downloadMb) } ?: ""),
        )
        SettingKey.LockAfter -> stringResource(R.string.import_change_short, lockAfterText((c.old as? Int) ?: 0), lockAfterText((c.new as? Int) ?: 0))
        SettingKey.QuickReplies -> stringResource(
            R.string.import_change_short,
            (c.old as? List<*>).orEmpty().joinToString(", ") { QuickReplies.short(it.toString()) },
            (c.new as? List<*>).orEmpty().joinToString(", ") { QuickReplies.short(it.toString()) }.ifEmpty { stringResource(R.string.import_none) },
        )
        else -> stringResource(R.string.import_change_short, settingValue(c.old), settingValue(c.new))
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(name, color = Colors.ink, fontSize = TextStyles.body, fontWeight = FontWeight.Medium)
        Text(text, color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
    }
}

@Composable
private fun settingName(key: SettingKey): String = stringResource(
    when (key) {
        SettingKey.Theme -> R.string.appearance_app
        SettingKey.TerminalColors -> R.string.appearance_terminal
        SettingKey.TextSize -> R.string.appearance_text_size
        SettingKey.TerminalFontScale -> R.string.backup_setting_font_scale
        SettingKey.AlertWaiting -> R.string.notifications_waiting
        SettingKey.AlertFailed -> R.string.notifications_failed
        SettingKey.AlertDone -> R.string.notifications_done
        SettingKey.AlertMessages -> R.string.notifications_messages
        SettingKey.AlertBells -> R.string.notifications_bells
        SettingKey.AlertVibrate -> R.string.notifications_vibrate
        SettingKey.AlertProgress -> R.string.notifications_progress
        SettingKey.BackgroundMode -> R.string.alerts_title
        SettingKey.HomeFilter -> R.string.backup_setting_filter
        SettingKey.ReopenLastTab -> R.string.reopen_last_tab
        SettingKey.ActivityBySession -> R.string.backup_setting_activity_by_session
        SettingKey.IncludeIdle -> R.string.activity_include_idle
        SettingKey.HostOrder -> R.string.backup_setting_order
        SettingKey.HostFolds -> R.string.backup_setting_folds
        SettingKey.HostBackground -> R.string.backup_setting_host_background
        SettingKey.QuickReplies -> R.string.quick_replies_title
        SettingKey.TailnetChoices -> R.string.backup_setting_tailnets
        SettingKey.Tile -> R.string.tile_group_all
        SettingKey.Templates -> R.string.backup_setting_templates
        SettingKey.TerminalFont -> R.string.terminal_font_title
        SettingKey.KeyBarAlways -> R.string.keybar_title
        SettingKey.CtrlC -> R.string.keybar_ctrl_c
        SettingKey.KeyArrangement -> R.string.arrange_title
        SettingKey.Gestures -> R.string.controls_group_gestures
        SettingKey.AppLock -> R.string.security_lock
        SettingKey.LockAfter -> R.string.security_lock_after
        SettingKey.BlockScreenshots -> R.string.security_block_screenshots
    },
)

/** A terminal font's name, by its id; the system's for none. */
@Composable
private fun fontName(id: String?): String =
    id?.let { FontCatalog.find(it)?.name } ?: stringResource(R.string.backup_font_system)

@Composable
private fun settingValue(v: Any?): String = when (v) {
    null -> stringResource(R.string.import_none)
    is Boolean -> stringResource(if (v) R.string.alerts_on else R.string.alerts_off)
    is Float -> "${Math.round(v * 100)}%"
    is AppTheme -> stringResource(choice(v).label)
    is TerminalColors -> stringResource(choice(v).label)
    is TextSize -> stringResource(choice(v).label)
    is CtrlCKey -> stringResource(choice(v).label)
    is String -> when (v) {
        HomeFilter.All.name -> stringResource(R.string.filter_all)
        HomeFilter.Activity.name -> stringResource(R.string.filter_activity)
        HomeFilter.NeedsYou.name -> stringResource(R.string.backup_filter_needs_you)
        HomeFilter.Running.name -> stringResource(R.string.backup_filter_running)
        else -> v
    }
    is HostBackground -> {
        val on = stringResource(R.string.alerts_on)
        val off = stringResource(R.string.alerts_off)
        val names = EVENTS.associate { (e, id) -> e to stringResource(id) }
        hostSummary(v) + v.overrides.entries.joinToString("") { (e, set) -> " · " + names[e].orEmpty() + " " + if (set) on else off }
    }
    else -> v.toString()
}

/** Why a backup cannot be imported, naming where in the file. */
@Composable
internal fun settingsProblemText(p: SettingsProblem): String = when (p.kind) {
    SettingsProblem.Kind.TooLarge -> stringResource(R.string.backup_problem_too_large, SettingsFormat.MAX_BYTES / 1024)
    SettingsProblem.Kind.NotJson -> stringResource(R.string.import_problem_not_json)
    SettingsProblem.Kind.MissingFormat -> stringResource(R.string.import_problem_missing_format, SettingsFormat.FORMAT)
    SettingsProblem.Kind.WrongFormat -> stringResource(R.string.import_problem_wrong_format, p.detail.orEmpty(), SettingsFormat.FORMAT)
    SettingsProblem.Kind.BadValue -> stringResource(R.string.backup_problem_value, p.field.orEmpty())
    SettingsProblem.Kind.UnknownHost -> stringResource(R.string.backup_problem_unknown_host, p.field.orEmpty(), p.detail.orEmpty())
    SettingsProblem.Kind.BadSecrets -> stringResource(R.string.backup_problem_secrets, p.field.orEmpty())
    SettingsProblem.Kind.Hosts, SettingsProblem.Kind.Plan -> p.hosts?.let { importProblemText(it) } ?: stringResource(R.string.import_problem_no_hosts)
}
