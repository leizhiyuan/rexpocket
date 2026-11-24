package me.leaver.rexpocket.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Settings › Backup: the phone's settings as one `rexpocket-settings/1` file, and that file read
 * back onto a phone. The hosts in it are a `rexpocket-hosts/1` list and go through Import hosts'
 * own plan and trust rules ([ImportPlan]); everything else is a preference, each shown as old → new
 * before anything changes. Never a tailnet's state or node key. SSH passwords and keys only when
 * the person asks, sealed with their passphrase ([BackupCrypto]).
 *
 * This file reads, checks, writes and plans; it changes nothing. See docs/design/settings-backup.md.
 */
object SettingsFormat {
    const val FORMAT = "rexpocket-settings/1"
    const val FORMAT_MARKER = "rexpocket-settings/"
    const val MAX_BYTES = 1024 * 1024

    /** What the sealed credentials are bound to: they open in a settings file of this version only. */
    internal val SECRETS_AAD = "$FORMAT secrets".toByteArray(Charsets.UTF_8)

    const val MAX_PASSWORD = 1024
    const val MAX_PRIVATE_KEY = 16 * 1024
    const val MIN_FONT_SCALE = 0.7f
    const val MAX_FONT_SCALE = 1.8f

    /** The longest tab (window) id a muted tab may have in a file. */
    const val MAX_TAB_ID = 128
    val HOME_FILTERS = listOf("All", "Activity", "NeedsYou", "Running")

    /** A terminal font's id, as the app's font list names it (`system`, `jetbrains-mono`, ...). */
    private val FONT_ID = Regex("[a-z0-9-]{1,40}")

    fun mentionsFormat(text: String): Boolean =
        text.contains(FORMAT_MARKER) || text.contains(FORMAT_MARKER.replace("/", "\\/"))

    /** Reads [bytes], refusing more than [MAX_BYTES] before decoding them. */
    fun parse(bytes: ByteArray): SettingsParse {
        if (bytes.size > MAX_BYTES) return failed(SettingsProblem.Kind.TooLarge)
        return parse(String(bytes, Charsets.UTF_8))
    }

    fun parse(text: String): SettingsParse {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return failed(SettingsProblem.Kind.TooLarge)
        val root = try {
            JSONTokener(text.trim()).nextValue() as? JSONObject
        } catch (e: JSONException) {
            null
        } ?: return failed(SettingsProblem.Kind.NotJson)
        return try {
            SettingsParse.Parsed(read(root))
        } catch (e: Refused) {
            SettingsParse.Failed(e.problem)
        }
    }

    private class Refused(val problem: SettingsProblem) : Exception()

    private fun refuse(kind: SettingsProblem.Kind, field: String? = null, detail: String? = null): Nothing =
        throw Refused(SettingsProblem(kind, field, detail?.take(80)))

    private fun read(root: JSONObject): SettingsBackup {
        val format = root.opt("format")
        if (format == null || format == JSONObject.NULL) refuse(SettingsProblem.Kind.MissingFormat)
        if (format != FORMAT) refuse(SettingsProblem.Kind.WrongFormat, detail = format.toString())

        // The hosts first: every other part names its hosts by the names given here.
        val hostsRoot = section(root, "hosts")
        val hosts = if (hostsRoot == null) emptyList() else when (val parsed = HostListFormat.read(hostsRoot, HostListRules.BACKUP)) {
            is HostImportParse.Failed -> throw Refused(SettingsProblem(SettingsProblem.Kind.Hosts, "hosts", hosts = parsed.problem))
            is HostImportParse.Parsed -> parsed.hosts
        }
        val names = hosts.associateBy { it.name.lowercase() }
        fun host(field: String, value: Any?): String {
            val name = value as? String ?: refuse(SettingsProblem.Kind.BadValue, field)
            return names[name.lowercase()]?.name ?: refuse(SettingsProblem.Kind.UnknownHost, field, name)
        }
        fun hostNames(field: String, o: JSONObject): List<String>? {
            val v = o.opt(field) ?: return null
            if (v == JSONObject.NULL) return null
            val arr = v as? JSONArray ?: refuse(SettingsProblem.Kind.BadValue, field)
            return List(arr.length()) { host(field, arr.opt(it)) }.distinctBy { it.lowercase() }
        }

        val appearance = section(root, "appearance")?.let { a ->
            Appearance(
                app = enumPref(a, "app", "appearance.app", AppTheme.entries.map { it.pref }, AppTheme.System.pref).let(AppTheme::fromPref),
                terminal = enumPref(a, "terminal", "appearance.terminal", TerminalColors.entries.map { it.pref }, TerminalColors.MatchApp.pref)
                    .let(TerminalColors::fromPref),
                text = enumPref(a, "text", "appearance.text", TextSize.entries.map { it.pref }, TextSize.System.pref).let(TextSize::fromPref),
            )
        }
        val fontScale = section(root, "terminal")?.let { t ->
            val v = t.opt("fontScale")
            if (v == null || v == JSONObject.NULL) null else {
                val n = (v as? Number)?.toFloat() ?: refuse(SettingsProblem.Kind.BadValue, "terminal.fontScale")
                if (n.isNaN() || n < MIN_FONT_SCALE || n > MAX_FONT_SCALE) refuse(SettingsProblem.Kind.BadValue, "terminal.fontScale", v.toString())
                n
            }
        }
        val terminalFont = section(root, "terminal")?.let { t ->
            val v = t.opt("font")
            if (v == null || v == JSONObject.NULL) null
            else (v as? String)?.takeIf { FONT_ID.matches(it) } ?: refuse(SettingsProblem.Kind.BadValue, "terminal.font", v.toString())
        }
        val keyBar = section(root, "keyBar")?.let(::readKeyBar)
        val gestures = section(root, "gestures")?.let(::readGestures)
        val security = section(root, "security")?.let { s ->
            val d = SecurityPrefs()
            val after = s.opt("lockAfterSeconds").let { v ->
                if (v == null || v == JSONObject.NULL) d.lockAfterSeconds
                else (v as? Number)?.takeIf { (it is Int || it is Long) && it.toLong() in SecurityPrefs.LOCK_AFTER.map(Int::toLong) }?.toInt()
                    ?: refuse(SettingsProblem.Kind.BadValue, "security.lockAfterSeconds", v.toString())
            }
            SecurityPrefs(
                lock = bool(s, "lock", "security") ?: d.lock,
                lockAfterSeconds = after,
                blockScreenshots = bool(s, "blockScreenshots", "security") ?: d.blockScreenshots,
            )
        }
        val notifications = section(root, "notifications")?.let { n ->
            val d = AlertPrefs()
            AlertPrefs(
                waiting = bool(n, "waiting", "notifications") ?: d.waiting,
                failed = bool(n, "failed", "notifications") ?: d.failed,
                done = bool(n, "done", "notifications") ?: d.done,
                messages = bool(n, "messages", "notifications") ?: d.messages,
                bells = bool(n, "bells", "notifications") ?: d.bells,
                vibrate = bool(n, "vibrate", "notifications") ?: d.vibrate,
                progress = bool(n, "progress", "notifications") ?: d.progress,
            )
        }
        var backgroundMode: Boolean? = null
        val hostBackground = LinkedHashMap<String, HostBackground>()
        section(root, "background")?.let { b ->
            backgroundMode = bool(b, "enabled", "background")
            val list = b.opt("hosts")
            if (list != null && list != JSONObject.NULL) {
                val arr = list as? JSONArray ?: refuse(SettingsProblem.Kind.BadValue, "background.hosts")
                for (i in 0 until arr.length()) {
                    val field = "background.hosts[${i + 1}]"
                    val h = arr.opt(i) as? JSONObject ?: refuse(SettingsProblem.Kind.BadValue, field)
                    val name = host("$field.name", h.opt("name"))
                    if (hostBackground.keys.any { it.equals(name, ignoreCase = true) }) refuse(SettingsProblem.Kind.BadValue, "$field.name", name)
                    val d = HostBackground()
                    val events = h.opt("events")
                    val overrides = LinkedHashMap<AlertEvent, Boolean>()
                    if (events != null && events != JSONObject.NULL) {
                        val e = events as? JSONObject ?: refuse(SettingsProblem.Kind.BadValue, "$field.events")
                        for (key in e.keys()) {
                            // An event this version does not know is a newer app's: left alone.
                            val event = AlertEvent.entries.firstOrNull { it.name == key } ?: continue
                            overrides[event] = e.opt(key) as? Boolean ?: refuse(SettingsProblem.Kind.BadValue, "$field.events.$key")
                        }
                    }
                    // The tabs muted on this host, by Rex's window id: added later, so a file may have none.
                    val muted = h.opt("mutedTabs").let { v ->
                        if (v == null || v == JSONObject.NULL) emptySet() else {
                            val arr = v as? JSONArray ?: refuse(SettingsProblem.Kind.BadValue, "$field.mutedTabs")
                            if (arr.length() > HostBackground.MAX_MUTED) refuse(SettingsProblem.Kind.BadValue, "$field.mutedTabs", arr.length().toString())
                            List(arr.length()) { j ->
                                (arr.opt(j) as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_TAB_ID }
                                    ?: refuse(SettingsProblem.Kind.BadValue, "$field.mutedTabs[${j + 1}]")
                            }.toSet()
                        }
                    }
                    hostBackground[name] = HostBackground(
                        stay = bool(h, "stay", field) ?: d.stay,
                        always = bool(h, "always", field) ?: d.always,
                        notify = bool(h, "notify", field) ?: d.notify,
                        overrides = overrides,
                        muted = muted,
                    )
                }
            }
        }
        val home = section(root, "home")?.let { h ->
            val filter = h.opt("filter").let { v ->
                if (v == null || v == JSONObject.NULL) null
                else (v as? String)?.takeIf { it in HOME_FILTERS } ?: refuse(SettingsProblem.Kind.BadValue, "home.filter", v.toString())
            }
            HomePrefs(
                filter = filter,
                reopenLastTab = bool(h, "reopenLastTab", "home"),
                activityBySession = bool(h, "activityBySession", "home"),
                includeIdle = bool(h, "includeIdle", "home"),
                hostOrder = hostNames("hostOrder", h),
                folded = hostNames("folded", h)?.toSet(),
                unfolded = hostNames("unfolded", h)?.toSet(),
            ).also { p ->
                val both = p.folded.orEmpty().filter { f -> p.unfolded.orEmpty().any { it.equals(f, ignoreCase = true) } }
                if (both.isNotEmpty()) refuse(SettingsProblem.Kind.BadValue, "home.folded", both.first())
            }
        }
        val quickReplies = root.opt("quickReplies").let { v ->
            if (v == null || v == JSONObject.NULL) null else {
                val arr = v as? JSONArray ?: refuse(SettingsProblem.Kind.BadValue, "quickReplies")
                if (arr.length() > QuickReplies.MAX_ITEMS) refuse(SettingsProblem.Kind.BadValue, "quickReplies", arr.length().toString())
                List(arr.length()) { i ->
                    val text = arr.opt(i) as? String ?: refuse(SettingsProblem.Kind.BadValue, "quickReplies[${i + 1}]")
                    if (text.length > QuickReplies.MAX_LENGTH) refuse(SettingsProblem.Kind.BadValue, "quickReplies[${i + 1}]")
                    QuickReplies.clean(text) ?: refuse(SettingsProblem.Kind.BadValue, "quickReplies[${i + 1}]")
                }.distinct()
            }
        }
        // Launch templates, each naming its host; added after version 1 was out, so optional.
        val templates = LinkedHashMap<String, MutableList<LaunchTemplate>>()
        root.opt("templates").let { v ->
            if (v == null || v == JSONObject.NULL) return@let
            val arr = v as? JSONArray ?: refuse(SettingsProblem.Kind.BadValue, "templates")
            for (i in 0 until arr.length()) {
                val field = "templates[${i + 1}]"
                val o = arr.opt(i) as? JSONObject ?: refuse(SettingsProblem.Kind.BadValue, field)
                val name = host("$field.host", o.opt("host"))
                val t = LaunchTemplate.fromJson(JSONObject().put("name", o.opt("name")).put("directory", o.opt("directory")).put("command", o.opt("command")))
                    ?: refuse(SettingsProblem.Kind.BadValue, field)
                val list = templates.getOrPut(name) { ArrayList() }
                if (list.size < LaunchTemplate.MAX_ITEMS) list += t
            }
        }
        val secrets = section(root, "secrets")?.let { s -> readSealed(s) }
        val tailnets = TailnetBackupFormat.readTailnets(root) { kind, field, detail -> refuse(kind, field, detail) }
        val tiles = TailnetBackupFormat.readTiles(root, { kind, field, detail -> refuse(kind, field, detail) }, ::host)
        return SettingsBackup(
            templates = templates,
            appearance = appearance,
            terminalFontScale = fontScale,
            terminalFont = terminalFont,
            notifications = notifications,
            backgroundMode = backgroundMode,
            home = home,
            hosts = hosts,
            hostBackground = hostBackground,
            quickReplies = quickReplies,
            keyBar = keyBar,
            gestures = gestures,
            security = security,
            secrets = secrets,
            tailnets = tailnets,
            tiles = tiles,
        )
    }

    /**
     * `keyBar`: the bar with a desk keyboard, the C-c key, and the arrangement as the phone stores
     * it. A key name this version does not know is a newer app's key and is left out, as a stored
     * one is; a group id or name that is not one is refused.
     */
    private fun readKeyBar(k: JSONObject): KeyBarPrefs {
        val ctrlC = k.opt("ctrlC").let { v ->
            if (v == null || v == JSONObject.NULL) null
            else (v as? String)?.let { s -> listOf(CtrlCKey.Tap, CtrlCKey.Hold).firstOrNull { it.pref == s } }
                ?: refuse(SettingsProblem.Kind.BadValue, "keyBar.ctrlC", v.toString())
        }
        fun names(field: String, o: JSONObject, path: String): String? {
            val v = o.opt(field) ?: return null
            if (v == JSONObject.NULL) return null
            val arr = v as? JSONArray ?: refuse(SettingsProblem.Kind.BadValue, path)
            if (arr.length() > 200) refuse(SettingsProblem.Kind.BadValue, path)
            return List(arr.length()) { i -> arr.opt(i) as? String ?: refuse(SettingsProblem.Kind.BadValue, "$path[${i + 1}]") }
                .filter { !it.contains(',') }
                .joinToString(",")
        }
        val order = names("order", k, "keyBar.order")
        val hidden = names("hidden", k, "keyBar.hidden")
        val groupsV = k.opt("groups")
        val groups = if (groupsV == null || groupsV == JSONObject.NULL) null else {
            val arr = groupsV as? JSONArray ?: refuse(SettingsProblem.Kind.BadValue, "keyBar.groups")
            if (arr.length() > KeyArrangement.MAX_GROUPS) refuse(SettingsProblem.Kind.BadValue, "keyBar.groups", arr.length().toString())
            List(arr.length()) { i ->
                val path = "keyBar.groups[${i + 1}]"
                val g = arr.opt(i) as? JSONObject ?: refuse(SettingsProblem.Kind.BadValue, path)
                val id = (g.opt("id") as? String)?.takeIf(BarKey::validGroupId) ?: refuse(SettingsProblem.Kind.BadValue, "$path.id")
                val name = g.opt("name").let { v ->
                    if (v == null || v == JSONObject.NULL) null
                    else (v as? String)?.let(KeyArrangement::cleanName) ?: refuse(SettingsProblem.Kind.BadValue, "$path.name")
                }
                Triple(id, name, names("keys", g, "$path.keys").orEmpty())
            }.distinctBy { it.first }
        }
        val arrangement = if (order == null && hidden == null && groups == null) null else KeyArrangement.fromStored(
            order = order,
            hidden = hidden,
            groups = groups?.joinToString(",") { it.first },
            groupKeys = { id -> groups?.firstOrNull { it.first == id }?.third },
            groupName = { id -> groups?.firstOrNull { it.first == id }?.second },
        )
        return KeyBarPrefs(always = bool(k, "always", "keyBar"), ctrlC = ctrlC, arrangement = arrangement)
    }

    /** `gestures`: each choice by the name the phone stores it under, and the two times (0 is the phone's own). */
    private fun readGestures(g: JSONObject): GestureSettings {
        val d = GestureSettings()
        fun <T> choice(field: String, parse: (String) -> T?): T? {
            val v = g.opt(field) ?: return null
            if (v == JSONObject.NULL) return null
            return (v as? String)?.let(parse) ?: refuse(SettingsProblem.Kind.BadValue, "gestures.$field", v.toString())
        }
        fun ms(field: String, range: IntRange): Int? {
            val v = g.opt(field) ?: return null
            if (v == JSONObject.NULL) return null
            val n = (v as? Number)?.takeIf { it is Int || it is Long }?.toInt() ?: refuse(SettingsProblem.Kind.BadValue, "gestures.$field", v.toString())
            if (n != 0 && n !in range) refuse(SettingsProblem.Kind.BadValue, "gestures.$field", v.toString())
            return n
        }
        return GestureSettings(
            tap = choice("tap") { s -> TapAction.entries.firstOrNull { it.pref == s } } ?: d.tap,
            doubleTap = choice("doubleTap") { s -> DoubleTapAction.entries.firstOrNull { it.pref == s } } ?: d.doubleTap,
            longPress = choice("longPress") { s -> LongPressAction.entries.firstOrNull { it.pref == s } } ?: d.longPress,
            waitForDoubleTap = bool(g, "waitForDoubleTap", "gestures") ?: d.waitForDoubleTap,
            doubleTapMs = ms("doubleTapMs", GestureSettings.DOUBLE_TAP_RANGE) ?: d.doubleTapMs,
            longPressMs = ms("longPressMs", GestureSettings.LONG_PRESS_RANGE) ?: d.longPressMs,
        )
    }

    private fun writeKeyBar(k: KeyBarPrefs): JSONObject = JSONObject().apply {
        k.always?.let { put("always", it) }
        k.ctrlC?.let { put("ctrlC", if (it == CtrlCKey.Hold) CtrlCKey.Hold.pref else CtrlCKey.Tap.pref) }
        k.arrangement?.let { a ->
            put("order", JSONArray((a.keys + a.rest).map { it.pref }))
            put("hidden", JSONArray(a.storedHidden().split(',').filter { it.isNotEmpty() }))
            put(
                "groups",
                JSONArray(a.groups.map { g -> JSONObject().put("id", g.id).put("name", g.name).put("keys", JSONArray(g.keys.map { it.pref })) }),
            )
        }
    }

    private fun writeGestures(g: GestureSettings): JSONObject = JSONObject()
        .put("tap", g.tap.pref)
        .put("doubleTap", g.doubleTap.pref)
        .put("longPress", g.longPress.pref)
        .put("waitForDoubleTap", g.waitForDoubleTap)
        .put("doubleTapMs", g.doubleTapMs)
        .put("longPressMs", g.longPressMs)

    private fun readSealed(s: JSONObject): SealedSecrets {
        fun bad(field: String): Nothing = refuse(SettingsProblem.Kind.BadSecrets, "secrets.$field")
        if (s.opt("kdf") != BackupCrypto.KDF) bad("kdf")
        if (s.opt("cipher") != BackupCrypto.CIPHER) bad("cipher")
        val iterations = (s.opt("iterations") as? Number)?.takeIf { it is Int || it is Long }?.toLong() ?: bad("iterations")
        if (iterations !in BackupCrypto.MIN_ITERATIONS..BackupCrypto.MAX_ITERATIONS) bad("iterations")
        val salt = (s.opt("salt") as? String)?.let(BackupCrypto::unb64)?.takeIf { it.size in BackupCrypto.SALT_BYTES..64 } ?: bad("salt")
        val nonce = (s.opt("nonce") as? String)?.let(BackupCrypto::unb64)?.takeIf { it.size == BackupCrypto.NONCE_BYTES } ?: bad("nonce")
        val data = (s.opt("data") as? String)?.let(BackupCrypto::unb64)
            ?.takeIf { it.size > BackupCrypto.TAG_BITS / 8 && it.size <= BackupCrypto.MAX_SEALED_BYTES } ?: bad("data")
        val count = (s.opt("hosts") as? Number)?.toInt()?.takeIf { it >= 0 } ?: 0
        return SealedSecrets(SealedBox(iterations.toInt(), salt, nonce, data), count)
    }

    /**
     * The credentials in [sealed], opened with [passphrase] and checked against [backup]'s hosts:
     * each names one SSH host of the file, once. Throws [WrongPassphraseException] for a wrong
     * passphrase; a box that opens but does not hold what it should is a [SettingsProblem].
     */
    fun open(backup: SettingsBackup, sealed: SealedSecrets, passphrase: String): OpenedSecrets {
        val plain = BackupCrypto.open(sealed.box, passphrase, SECRETS_AAD)
        try {
            val root = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull()
                ?: return OpenedSecrets.Failed(SettingsProblem(SettingsProblem.Kind.BadSecrets, "secrets"))
            val arr = root.optJSONArray("hosts") ?: return OpenedSecrets.Failed(SettingsProblem(SettingsProblem.Kind.BadSecrets, "secrets.hosts"))
            val byName = backup.hosts.associateBy { it.name.lowercase() }
            val out = LinkedHashMap<String, HostSecret>()
            for (i in 0 until arr.length()) {
                fun bad(field: String) = OpenedSecrets.Failed(SettingsProblem(SettingsProblem.Kind.BadSecrets, "secrets.hosts[${i + 1}].$field"))
                val o = arr.optJSONObject(i) ?: return bad("name")
                val name = o.opt("name") as? String ?: return bad("name")
                val host = byName[name.lowercase()] ?: return OpenedSecrets.Failed(
                    SettingsProblem(SettingsProblem.Kind.UnknownHost, "secrets.hosts[${i + 1}].name", name.take(80)),
                )
                if (!host.address.ssh || out.containsKey(host.name)) return bad("name")
                fun text(field: String, max: Int): String? {
                    val v = o.opt(field)
                    if (v == null || v == JSONObject.NULL) return null
                    val t = v as? String ?: return "\u0000"
                    return t.takeIf { it.length <= max && it.isNotEmpty() } ?: "\u0000"
                }
                val password = text("password", MAX_PASSWORD)
                val key = text("privateKey", MAX_PRIVATE_KEY)
                val passphraseOfKey = text("passphrase", MAX_PASSWORD)
                if (password == "\u0000") return bad("password")
                if (passphraseOfKey == "\u0000") return bad("passphrase")
                if (key == "\u0000" || (key != null && !key.trimStart().startsWith("-----BEGIN "))) return bad("privateKey")
                if (password == null && key == null) return bad("privateKey")
                out[host.name] = HostSecret(host.name, password, key, passphraseOfKey)
            }
            return OpenedSecrets.Opened(out)
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Writes [snapshot] as a `rexpocket-settings/1` file, which [parse] reads back. With
     * [passphrase], [SettingsSnapshot.secrets] go in sealed; without, no credential at all.
     */
    fun write(snapshot: SettingsSnapshot, passphrase: String? = null, iterations: Int = BackupCrypto.ITERATIONS): SettingsExport {
        val (hostList, hostExport) = HostListFormat.writeList(snapshot.hosts, HostListRules.BACKUP)
        val written = hostExport.written.toSet()
        val root = JSONObject().put("format", FORMAT)
        snapshot.appearance?.let { a ->
            root.put("appearance", JSONObject().put("app", a.app.pref).put("terminal", a.terminal.pref).put("text", a.text.pref))
        }
        val terminal = JSONObject()
        snapshot.terminalFontScale?.let { terminal.put("fontScale", it.toDouble()) }
        snapshot.terminalFont?.takeIf { FONT_ID.matches(it) }?.let { terminal.put("font", it) }
        if (terminal.length() > 0) root.put("terminal", terminal)
        snapshot.keyBar?.let { root.put("keyBar", writeKeyBar(it)) }
        snapshot.gestures?.let { root.put("gestures", writeGestures(it)) }
        snapshot.security?.let { s ->
            root.put(
                "security",
                JSONObject().put("lock", s.lock).put("lockAfterSeconds", SecurityPrefs.cleanLockAfter(s.lockAfterSeconds))
                    .put("blockScreenshots", s.blockScreenshots),
            )
        }
        snapshot.notifications?.let { n ->
            root.put(
                "notifications",
                JSONObject().put("waiting", n.waiting).put("failed", n.failed).put("done", n.done)
                    .put("messages", n.messages).put("bells", n.bells).put("vibrate", n.vibrate)
                    .put("progress", n.progress),
            )
        }
        val background = JSONObject()
        snapshot.backgroundMode?.let { background.put("enabled", it) }
        val perHost = JSONArray()
        for ((name, b) in snapshot.hostBackground) {
            if (name !in written || b.isDefault) continue
            perHost.put(
                JSONObject().put("name", name).put("stay", b.stay).put("always", b.always).put("notify", b.notify)
                    .put("events", JSONObject().apply { b.overrides.forEach { (e, on) -> put(e.name, on) } })
                    .apply { if (b.muted.isNotEmpty()) put("mutedTabs", JSONArray(b.muted.toList())) },
            )
        }
        if (perHost.length() > 0) background.put("hosts", perHost)
        if (background.length() > 0) root.put("background", background)
        snapshot.home?.let { h ->
            val home = JSONObject()
            h.filter?.let { home.put("filter", it) }
            h.reopenLastTab?.let { home.put("reopenLastTab", it) }
            h.activityBySession?.let { home.put("activityBySession", it) }
            h.includeIdle?.let { home.put("includeIdle", it) }
            h.hostOrder?.let { order -> home.put("hostOrder", JSONArray(order.filter { it in written })) }
            h.folded?.let { f -> home.put("folded", JSONArray(f.filter { it in written })) }
            h.unfolded?.let { f -> home.put("unfolded", JSONArray(f.filter { it in written })) }
            root.put("home", home)
        }
        root.put("hosts", hostList)
        snapshot.quickReplies?.let { root.put("quickReplies", JSONArray(it.take(QuickReplies.MAX_ITEMS))) }
        TailnetBackupFormat.writeTailnets(root, snapshot.tailnets)
        TailnetBackupFormat.writeTiles(root, snapshot.tiles, written)
        val templates = JSONArray()
        for ((name, list) in snapshot.templates) {
            if (name !in written) continue
            list.take(LaunchTemplate.MAX_ITEMS).forEach { t ->
                templates.put(JSONObject().put("host", name).put("name", t.name).put("directory", t.directory).put("command", t.command))
            }
        }
        if (templates.length() > 0) root.put("templates", templates)
        var sealedCount = 0
        if (passphrase != null) {
            // Only an SSH host signs in: a Rex port has nothing to carry.
            val sshNames = snapshot.hosts.filter { it.address.ssh && it.name in written }.map { it.name }.toSet()
            val secrets = snapshot.secrets.filter { it.name in sshNames && it.usable }
            val plain = JSONObject().put(
                "hosts",
                JSONArray().apply {
                    secrets.forEach { s ->
                        put(
                            JSONObject().put("name", s.name)
                                .put("password", s.password ?: JSONObject.NULL)
                                .put("privateKey", s.privateKey ?: JSONObject.NULL)
                                .put("passphrase", s.passphrase ?: JSONObject.NULL),
                        )
                    }
                },
            ).toString().toByteArray(Charsets.UTF_8)
            val box = try {
                BackupCrypto.seal(plain, passphrase, SECRETS_AAD, iterations)
            } finally {
                plain.fill(0)
            }
            sealedCount = secrets.size
            root.put(
                "secrets",
                JSONObject()
                    .put("kdf", BackupCrypto.KDF)
                    .put("iterations", box.iterations)
                    .put("salt", BackupCrypto.b64(box.salt))
                    .put("cipher", BackupCrypto.CIPHER)
                    .put("nonce", BackupCrypto.b64(box.nonce))
                    .put("hosts", sealedCount)
                    .put("data", BackupCrypto.b64(box.data)),
            )
        }
        return SettingsExport(root.toString(2), hostExport.written, hostExport.left, sealedCount, hostExport.reasons)
    }

    private fun section(root: JSONObject, key: String): JSONObject? {
        val v = root.opt(key) ?: return null
        if (v == JSONObject.NULL) return null
        return v as? JSONObject ?: refuse(SettingsProblem.Kind.BadValue, key)
    }

    private fun bool(o: JSONObject, key: String, section: String): Boolean? {
        val v = o.opt(key) ?: return null
        if (v == JSONObject.NULL) return null
        return v as? Boolean ?: refuse(SettingsProblem.Kind.BadValue, "$section.$key", v.toString())
    }

    private fun enumPref(o: JSONObject, key: String, field: String, allowed: List<String>, default: String): String {
        val v = o.opt(key) ?: return default
        if (v == JSONObject.NULL) return default
        return (v as? String)?.takeIf { it in allowed } ?: refuse(SettingsProblem.Kind.BadValue, field, v.toString())
    }

    private fun failed(kind: SettingsProblem.Kind) = SettingsParse.Failed(SettingsProblem(kind))
}

/** The Home's own preferences, its hosts named as the file names them. */
data class HomePrefs(
    val filter: String? = null,
    val reopenLastTab: Boolean? = null,
    /** The Activity view by session (true) or by priority. */
    val activityBySession: Boolean? = null,
    /** The Activity view's Include idle. */
    val includeIdle: Boolean? = null,
    val hostOrder: List<String>? = null,
    val folded: Set<String>? = null,
    val unfolded: Set<String>? = null,
)

/**
 * Settings › Terminal controls' key bar: shown with a desk keyboard, the C-c key's tap or hold, and
 * the keys and groups. Each null leaves the phone's as it is.
 */
data class KeyBarPrefs(
    val always: Boolean? = null,
    val ctrlC: CtrlCKey? = null,
    val arrangement: KeyArrangement? = null,
)

/** A settings file, read and checked. Every part is optional: a missing one leaves the phone's as it is. */
data class SettingsBackup(
    val appearance: Appearance? = null,
    val terminalFontScale: Float? = null,
    /** The terminal font's id; one this version's font list does not have is left alone. */
    val terminalFont: String? = null,
    val notifications: AlertPrefs? = null,
    val backgroundMode: Boolean? = null,
    val home: HomePrefs? = null,
    val hosts: List<ImportedHost> = emptyList(),
    /** Each host's background choices, by its name in [hosts]. */
    val hostBackground: Map<String, HostBackground> = emptyMap(),
    /** Settings › Quick replies, in order: the replies and prompts the person sends often. */
    val quickReplies: List<String>? = null,
    val keyBar: KeyBarPrefs? = null,
    val gestures: GestureSettings? = null,
    val security: SecurityPrefs? = null,
    val secrets: SealedSecrets? = null,
    /** Each tailnet account's choices, by backend and account name; optional, added later. */
    val tailnets: List<TailnetChoices>? = null,
    /** The Quick Settings tiles' bindings, by slot; optional, added later. */
    val tiles: List<TileBinding>? = null,
    /** Each host's launch templates, by its name in [hosts], in order. A host the file has none for keeps its own. */
    val templates: Map<String, List<LaunchTemplate>> = emptyMap(),
)

/** The sealed credentials of a file, and how many hosts it says they are for (shown before it is opened). */
class SealedSecrets(val box: SealedBox, val hosts: Int) {
    override fun toString(): String = "SealedSecrets($hosts hosts)"
}

/** One host's credentials. Its [toString] names the host only. */
class HostSecret(val name: String, val password: String?, val privateKey: String?, val passphrase: String?) {
    val usable: Boolean get() = !password.isNullOrEmpty() || !privateKey.isNullOrBlank()

    override fun toString(): String = "HostSecret($name)"

    override fun equals(other: Any?): Boolean =
        other is HostSecret && other.name == name && other.password == password && other.privateKey == privateKey && other.passphrase == passphrase

    override fun hashCode(): Int = name.hashCode()
}

sealed interface OpenedSecrets {
    /** By host name, as [SettingsBackup.hosts] spells it. */
    data class Opened(val hosts: Map<String, HostSecret>) : OpenedSecrets
    data class Failed(val problem: SettingsProblem) : OpenedSecrets
}

/** What the phone has, for Export: hosts as Export hosts writes them, the rest by host name. */
data class SettingsSnapshot(
    val appearance: Appearance? = null,
    val terminalFontScale: Float? = null,
    val terminalFont: String? = null,
    val notifications: AlertPrefs? = null,
    val backgroundMode: Boolean? = null,
    val home: HomePrefs? = null,
    val hosts: List<ExportedHost> = emptyList(),
    val hostBackground: Map<String, HostBackground> = emptyMap(),
    val quickReplies: List<String>? = null,
    val keyBar: KeyBarPrefs? = null,
    val gestures: GestureSettings? = null,
    val security: SecurityPrefs? = null,
    /** Written only when a passphrase is given. */
    val secrets: List<HostSecret> = emptyList(),
    val tailnets: List<TailnetChoices>? = null,
    /** By host name (as in [hosts]) or tailnet; a tile whose host is not written is left out. */
    val tiles: List<TileBinding>? = null,
    /** Each host's launch templates, by host name. */
    val templates: Map<String, List<LaunchTemplate>> = emptyMap(),
)

/** A written settings file: its text, the hosts in it and those left out (each with why), and how many hosts' credentials went in sealed. */
data class SettingsExport(
    val text: String,
    val hosts: List<String>,
    val left: List<String>,
    val sealedHosts: Int,
    val reasons: Map<String, HostListExport.Reason> = emptyMap(),
) {
    fun leftFor(reason: HostListExport.Reason): List<String> = left.filter { reasons[it] == reason }

    override fun toString(): String = "SettingsExport(${hosts.size} hosts, ${left.size} left out, $sealedHosts sealed)"
}

data class SettingsProblem(
    val kind: Kind,
    /** Where in the file, as a path: `appearance.app`, `background.hosts[2].name`. */
    val field: String? = null,
    val detail: String? = null,
    /** The host list's own problem, for [Kind.Hosts]. */
    val hosts: ImportProblem? = null,
) {
    enum class Kind {
        TooLarge, NotJson, MissingFormat, WrongFormat, BadValue, Hosts, UnknownHost, BadSecrets,

        /** Planning the hosts failed (an entry fits two of the phone's hosts). */
        Plan,
    }

    override fun toString(): String = listOfNotNull(kind.name, field, detail, hosts?.toString()).joinToString(" · ")
}

sealed interface SettingsParse {
    data class Parsed(val backup: SettingsBackup) : SettingsParse
    data class Failed(val problem: SettingsProblem) : SettingsParse
}

/** The phone's settings as an import compares them, its hosts by id. */
data class CurrentSettings(
    val appearance: Appearance = Appearance(),
    val terminalFontScale: Float = 1f,
    val notifications: AlertPrefs = AlertPrefs(),
    val backgroundMode: Boolean = false,
    val homeFilter: String = "All",
    val reopenLastTab: Boolean = false,
    val activityBySession: Boolean = false,
    val includeIdle: Boolean = false,
    /** The phone's hosts, in its order. */
    val hosts: List<KnownHost> = emptyList(),
    val hostBackground: Map<String, HostBackground> = emptyMap(),
    val folded: Set<String> = emptySet(),
    val unfolded: Set<String> = emptySet(),
    val quickReplies: List<String> = QuickReplies.DEFAULTS,
    /** The phone's tailnet accounts and their choices. */
    val tailnets: List<TailnetChoices> = emptyList(),
    /** The phone's tiles, a host's by its name. */
    val tiles: List<TileBinding> = emptyList(),
    /** Each host's launch templates, by host id. */
    val templates: Map<String, List<LaunchTemplate>> = emptyMap(),
    val terminalFont: String = "system",
    val keyBarAlways: Boolean = false,
    val ctrlC: CtrlCKey = CtrlCKey.Tap,
    val keyArrangement: KeyArrangement = KeyArrangement(),
    val gestures: GestureSettings = GestureSettings(),
    val security: SecurityPrefs = SecurityPrefs(),
)

enum class SettingKey {
    Theme, TerminalColors, TextSize, TerminalFontScale, TerminalFont,
    AlertWaiting, AlertFailed, AlertDone, AlertMessages, AlertBells, AlertVibrate, AlertProgress,
    BackgroundMode, HomeFilter, ReopenLastTab, ActivityBySession, IncludeIdle, HostOrder, HostFolds, HostBackground, QuickReplies,

    /** One tailnet account's choices: [SettingChange.host] names the account, old and new are [TailnetPolicy] (old null for a new account). */
    TailnetChoices,

    /** One tile's binding: [SettingChange.host] is its slot, old and new are [TileBinding] (old null for a free tile). */
    Tile,
    /** One host's launch templates: old and new are their names, in order. */
    Templates,
    KeyBarAlways, CtrlC, KeyArrangement, Gestures, AppLock, LockAfter, BlockScreenshots,
}

/** One preference an import changes: [old] → [new], for [host] when it is one host's. */
data class SettingChange(val key: SettingKey, val old: Any?, val new: Any?, val host: String? = null)

/**
 * What applying [backup] would change on a phone that has [current], beside the hosts' own plan:
 * only what differs. Hosts held back by the plan (an unconfirmed key change) are left out by the
 * caller at apply time, as their other changes are.
 */
fun settingChanges(backup: SettingsBackup, current: CurrentSettings, plan: ImportPlan): List<SettingChange> = buildList {
    fun <T> change(key: SettingKey, old: T, new: T?, host: String? = null) {
        if (new != null && old != new) add(SettingChange(key, old, new, host))
    }
    backup.appearance?.let { a ->
        change(SettingKey.Theme, current.appearance.app, a.app)
        change(SettingKey.TerminalColors, current.appearance.terminal, a.terminal)
        change(SettingKey.TextSize, current.appearance.text, a.text)
    }
    change(SettingKey.TerminalFontScale, current.terminalFontScale, backup.terminalFontScale)
    change(SettingKey.TerminalFont, current.terminalFont, backup.terminalFont)
    backup.keyBar?.let { k ->
        change(SettingKey.KeyBarAlways, current.keyBarAlways, k.always)
        change(SettingKey.CtrlC, current.ctrlC, k.ctrlC)
        change(SettingKey.KeyArrangement, current.keyArrangement, k.arrangement)
    }
    change(SettingKey.Gestures, current.gestures, backup.gestures)
    backup.security?.let { s ->
        change(SettingKey.AppLock, current.security.lock, s.lock)
        change(SettingKey.LockAfter, current.security.lockAfterSeconds, s.lockAfterSeconds)
        change(SettingKey.BlockScreenshots, current.security.blockScreenshots, s.blockScreenshots)
    }
    backup.notifications?.let { n ->
        val c = current.notifications
        change(SettingKey.AlertWaiting, c.waiting, n.waiting)
        change(SettingKey.AlertFailed, c.failed, n.failed)
        change(SettingKey.AlertDone, c.done, n.done)
        change(SettingKey.AlertMessages, c.messages, n.messages)
        change(SettingKey.AlertBells, c.bells, n.bells)
        change(SettingKey.AlertVibrate, c.vibrate, n.vibrate)
        change(SettingKey.AlertProgress, c.progress, n.progress)
    }
    change(SettingKey.BackgroundMode, current.backgroundMode, backup.backgroundMode)
    change(SettingKey.QuickReplies, current.quickReplies, backup.quickReplies)
    backup.home?.let { h ->
        change(SettingKey.HomeFilter, current.homeFilter, h.filter)
        change(SettingKey.ReopenLastTab, current.reopenLastTab, h.reopenLastTab)
        change(SettingKey.ActivityBySession, current.activityBySession, h.activityBySession)
        change(SettingKey.IncludeIdle, current.includeIdle, h.includeIdle)
    }
    backup.tailnets?.forEach { t ->
        val now = current.tailnets.firstOrNull { it.sameAccount(t) }
        if (now?.policy != t.policy || now == null) add(SettingChange(SettingKey.TailnetChoices, now?.policy, t.policy, host = t.label(backendName(t.backend))))
    }
    backup.tiles?.forEach { t ->
        val now = current.tiles.firstOrNull { it.slot == t.slot }
        if (now == null || !now.same(t)) add(SettingChange(SettingKey.Tile, now, t, host = t.slot.toString()))
    }
    val planned = plan.hosts.associateBy { it.imported.name }
    for ((name, next) in backup.hostBackground) {
        val existing = planned[name]?.existing
        val now = existing?.let { current.hostBackground[it.id] } ?: HostBackground()
        change(SettingKey.HostBackground, now, next, host = name)
    }
    for ((name, next) in backup.templates) {
        val now = planned[name]?.existing?.let { current.templates[it.id] }.orEmpty()
        // The same templates are no change, whatever ids the phone gave them.
        fun same(a: List<LaunchTemplate>, b: List<LaunchTemplate>) = a.map { Triple(it.name, it.directory, it.command) } == b.map { Triple(it.name, it.directory, it.command) }
        if (!same(now, next)) add(SettingChange(SettingKey.Templates, now.map { it.name }, next.map { it.name }, host = name))
    }
    // A new host comes after the phone's own in Import hosts; the file's order is then laid on.
    val names = current.hosts.map { k -> plan.hosts.firstOrNull { it.existing?.id == k.id }?.imported?.name ?: k.name } +
        plan.hosts.filter { it.existing == null }.map { it.imported.name }
    backup.home?.hostOrder?.let { order ->
        val before = current.hosts.map { it.name }
        val after = inFileOrder(names, { it }, order)
        if (after != names) change(SettingKey.HostOrder, before, after)
    }
    backup.home?.let { h ->
        if (h.folded == null && h.unfolded == null) return@let
        val folded = { n: String -> h.folded.orEmpty().any { it.equals(n, ignoreCase = true) } }
        val unfolded = { n: String -> h.unfolded.orEmpty().any { it.equals(n, ignoreCase = true) } }
        val changed = plan.hosts.filter { p ->
            val id = p.existing?.id
            val was = when {
                id == null -> null
                id in current.folded -> true
                id in current.unfolded -> false
                else -> null
            }
            val will = when {
                folded(p.imported.name) -> true
                unfolded(p.imported.name) -> false
                else -> was
            }
            was != will
        }.map { it.imported.name }
        if (changed.isNotEmpty()) add(SettingChange(SettingKey.HostFolds, null, changed))
    }
}

/**
 * [hosts] with those [order] names laid in its order, in the places those hosts take now; every
 * other host keeps its place. Nothing is added or removed.
 */
fun <H> inFileOrder(hosts: List<H>, name: (H) -> String, order: List<String>): List<H> {
    val rank = order.withIndex().associate { (i, n) -> n.lowercase() to i }
    val named = hosts.filter { name(it).lowercase() in rank }.sortedBy { rank.getValue(name(it).lowercase()) }
    var next = 0
    return hosts.map { h -> if (name(h).lowercase() in rank) named[next++] else h }
}

/**
 * The credentials [secrets] give [host]: only to an SSH host that is new, or the phone's with
 * nothing to sign in with. A saved host keeps its password or key, as in Import hosts.
 */
fun credentialsFor(host: PlannedHost, secrets: Map<String, HostSecret>): HostSecret? {
    if (!host.imported.address.ssh) return null
    val existing = host.existing
    if (existing != null && existing.hasCredentials && existing.address.ssh) return null
    return secrets[host.imported.name]?.takeIf { it.usable }
}

/** A backend's name as the screens show it, from its storage key. */
internal fun backendName(key: String): String = TailnetBackend.entries.firstOrNull { it.storageKey == key }?.displayName ?: key
