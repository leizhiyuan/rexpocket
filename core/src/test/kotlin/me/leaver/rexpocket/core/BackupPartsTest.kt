package me.leaver.rexpocket.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts a settings backup gained after its first version - the key bar, the gestures, the
 * terminal font, Security, and each host's full list of other addresses, Try other addresses and
 * tailnet account - written and read back the same, and a file from before them still read as it
 * was. See docs/design/settings-backup.md.
 */
class BackupPartsTest {
    private val ssh = ExportedHost(
        "agents", ImportAddress("me", "agents.example.com", 22, true),
        listOf(ImportAddress("me", "100.92.1.2", 22, true), ImportAddress("me", "agents.local", 2222, true), ImportAddress("me", "10.0.0.5", 22, true)),
        null, null, tryOthers = false,
    )
    private val tailnetHost = ExportedHost(
        "studio", ImportAddress(null, "studio.example.ts.net", 443, plaintext = false),
        listOf(ImportAddress(null, "100.100.1.2", 7297, plaintext = true)), null, null, tailnetAccount = "Work",
    )

    /** A bar nobody has by default: a key moved, one taken off, Shift put on, a group of its own. */
    private val arrangement = KeyArrangement()
        .moveTo(BarKey.Tab, 0)
        .remove(BarKey.Slash)
        .add(BarKey.Shift)
        .addGroup("Git")
        .let { a -> a.addToGroup(a.groups.last().id, BarKey.Escape).addToGroup(a.groups.last().id, BarKey.Tab) }
        .addPreset(KeyPreset.entries.first { it != KeyPreset.Fn })

    private val gestures = GestureSettings(
        tap = TapAction.Toggle,
        doubleTap = DoubleTapAction.Nothing,
        longPress = LongPressAction.FullScreen,
        waitForDoubleTap = true,
        doubleTapMs = 250,
        longPressMs = 700,
    )

    private val snapshot = SettingsSnapshot(
        terminalFontScale = 1.1f,
        terminalFont = "jetbrains-nerd",
        keyBar = KeyBarPrefs(always = true, ctrlC = CtrlCKey.Hold, arrangement = arrangement),
        gestures = gestures,
        security = SecurityPrefs(lock = true, lockAfterSeconds = 300, blockScreenshots = true),
        hosts = listOf(ssh, tailnetHost),
    )

    private fun parsed(text: String): SettingsBackup = when (val p = SettingsFormat.parse(text)) {
        is SettingsParse.Parsed -> p.backup
        is SettingsParse.Failed -> throw AssertionError("refused: ${p.problem}")
    }

    private fun problem(text: String): SettingsProblem = when (val p = SettingsFormat.parse(text)) {
        is SettingsParse.Parsed -> throw AssertionError("expected a refusal")
        is SettingsParse.Failed -> p.problem
    }

    @Test
    fun theNewPartsRoundTrip() {
        val b = parsed(SettingsFormat.write(snapshot).text)
        assertEquals("jetbrains-nerd", b.terminalFont)
        assertEquals(true, b.keyBar!!.always)
        assertEquals(CtrlCKey.Hold, b.keyBar!!.ctrlC)
        assertEquals(arrangement, b.keyBar!!.arrangement)
        assertEquals(gestures, b.gestures)
        assertEquals(SecurityPrefs(lock = true, lockAfterSeconds = 300, blockScreenshots = true), b.security)

        // Read back onto the phone it came from, none of them is a change.
        val current = CurrentSettings(
            terminalFontScale = 1.1f,
            terminalFont = "jetbrains-nerd",
            keyBarAlways = true,
            ctrlC = CtrlCKey.Hold,
            keyArrangement = arrangement,
            gestures = gestures,
            security = SecurityPrefs(lock = true, lockAfterSeconds = 300, blockScreenshots = true),
        )
        assertEquals(emptyList<SettingChange>(), settingChanges(b, current, ImportPlan(emptyList())))
        // Onto a fresh phone, each is.
        val keys = settingChanges(b, CurrentSettings(), ImportPlan(emptyList())).map { it.key }.toSet()
        assertTrue(
            keys.containsAll(
                listOf(
                    SettingKey.TerminalFont, SettingKey.KeyBarAlways, SettingKey.CtrlC, SettingKey.KeyArrangement,
                    SettingKey.Gestures, SettingKey.AppLock, SettingKey.LockAfter, SettingKey.BlockScreenshots,
                ),
            ),
        )
    }

    @Test
    fun theDefaultsRoundTripToo() {
        val defaults = SettingsSnapshot(
            terminalFont = "system",
            keyBar = KeyBarPrefs(always = false, ctrlC = CtrlCKey.Tap, arrangement = KeyArrangement()),
            gestures = GestureSettings(),
            security = SecurityPrefs(),
        )
        val b = parsed(SettingsFormat.write(defaults).text)
        assertEquals(KeyArrangement(), b.keyBar!!.arrangement)
        assertEquals(GestureSettings(), b.gestures)
        assertEquals(SecurityPrefs(), b.security)
        assertEquals(emptyList<SettingChange>(), settingChanges(b, CurrentSettings(), ImportPlan(emptyList())))
    }

    @Test
    fun eachHostKeepsAllItsOtherAddressesInOrderTryOthersAndItsAccount() {
        val b = parsed(SettingsFormat.write(snapshot).text)
        val agents = b.hosts.single { it.name == "agents" }
        assertEquals(ssh.fallbacks, agents.fallbacks)
        assertEquals(false, agents.tryOthers)
        assertNull(agents.tailnetAccount)
        val studio = b.hosts.single { it.name == "studio" }
        assertEquals(tailnetHost.fallbacks.single().toString(), studio.fallbacks.single().toString())
        assertEquals(true, studio.tryOthers)
        assertEquals("Work", studio.tailnetAccount)

        // The phone that wrote it sees every host as it is.
        val phone = listOf(
            KnownHost("1", "agents", ssh.address, ssh.fallbacks, null, true, tryOthers = false),
            KnownHost("2", "studio", tailnetHost.address, tailnetHost.fallbacks, null, true, tailnetAccount = "work"),
        )
        val plan = (ImportPlan.of(HostImportParse.Parsed(b.hosts), phone) as HostImportPlan.Planned).plan
        assertEquals(listOf(ImportStatus.Unchanged, ImportStatus.Unchanged), plan.hosts.map { it.status })

        // A phone whose agents tries its main address only, and whose studio follows the active account.
        val other = listOf(
            KnownHost("1", "agents", ssh.address, ssh.fallbacks.reversed(), null, true, tryOthers = true),
            KnownHost("2", "studio", tailnetHost.address, tailnetHost.fallbacks, null, true),
        )
        val changed = (ImportPlan.of(HostImportParse.Parsed(b.hosts), other) as HostImportPlan.Planned).plan
        assertEquals(listOf(ImportField.Fallbacks, ImportField.TryOthers), changed.hosts[0].changes.map { it.field })
        assertEquals(listOf(ImportField.TailnetAccount), changed.hosts[1].changes.map { it.field })
        assertEquals("Work", changed.hosts[1].changes.single().new)
    }

    @Test
    fun aHostListFileCarriesTheSameFieldsAndOldReadersKeepTheirUri() {
        val export = HostListFormat.write(listOf(ssh))
        val entry = JSONObject(export.text).getJSONArray("hosts").getJSONObject(0)
        // `uri` is still the first other address, as a reader from before `otherUris` takes it.
        assertEquals("ssh://me@100.92.1.2:22", entry.getString("uri"))
        assertEquals(3, entry.getJSONArray("otherUris").length())
        val back = (HostListFormat.parse(export.text) as HostImportParse.Parsed).hosts.single()
        assertEquals(ssh.fallbacks, back.fallbacks)
        assertEquals(false, back.tryOthers)
    }

    /** A backup exactly as the first version wrote it: no new part, and hosts with `uri` alone. */
    @Test
    fun aBackupFromBeforeTheNewPartsStillLoads() {
        val old = """
            {
              "format": "rexpocket-settings/1",
              "appearance": { "app": "dark", "terminal": "app", "text": "115" },
              "terminal": { "fontScale": 1.2 },
              "notifications": { "waiting": true, "failed": true, "done": true, "messages": true, "bells": false, "vibrate": true },
              "background": { "enabled": false },
              "home": { "filter": "NeedsYou", "reopenLastTab": false, "hostOrder": ["agents", "Desk mac"], "folded": ["Desk mac"], "unfolded": [] },
              "hosts": {
                "format": "rexpocket-hosts/1",
                "hosts": [
                  { "name": "agents", "uri": "ssh://me@100.92.1.2:22", "dnsUri": "ssh://me@agents.example.ts.net:22", "hostKeyFingerprint": null, "keyComment": "rexpocket:agents" },
                  { "name": "Desk mac", "uri": null, "dnsUri": "ws://desk.local:7297", "hostKeyFingerprint": null, "keyComment": null }
                ]
              },
              "quickReplies": ["yes", "continue"]
            }
        """.trimIndent()
        val b = parsed(old)
        assertEquals(1.2f, b.terminalFontScale!!, 0.0001f)
        assertNull(b.terminalFont)
        assertNull(b.keyBar)
        assertNull(b.gestures)
        assertNull(b.security)
        val agents = b.hosts[0]
        assertEquals(listOf(ImportAddress("me", "100.92.1.2", 22, true)), agents.fallbacks)
        assertNull(agents.tryOthers)
        assertNull(agents.tailnetAccount)
        // A file without the new fields changes none of them, nor a host's Try other addresses.
        val keys = settingChanges(b, CurrentSettings(keyBarAlways = true, gestures = gestures), ImportPlan(emptyList())).map { it.key }
        assertFalse(SettingKey.Gestures in keys || SettingKey.KeyBarAlways in keys || SettingKey.AppLock in keys || SettingKey.TerminalFont in keys)
        val phone = listOf(KnownHost("1", "agents", agents.address, agents.fallbacks, null, true, tryOthers = false, tailnetAccount = "Work"))
        val plan = (ImportPlan.of(HostImportParse.Parsed(listOf(agents)), phone) as HostImportPlan.Planned).plan
        assertEquals(ImportStatus.Unchanged, plan.hosts.single().status)
    }

    @Test
    fun aKeyANewerAppAddedIsLeftOutAndABadValueIsRefused() {
        val root = JSONObject(SettingsFormat.write(snapshot).text)
        root.getJSONObject("keyBar").getJSONArray("order").put("teleport")
        assertEquals(arrangement, parsed(root.toString()).keyBar!!.arrangement)

        fun with(edit: JSONObject.() -> Unit): SettingsProblem = problem(JSONObject(SettingsFormat.write(snapshot).text).apply(edit).toString())
        assertEquals("keyBar.ctrlC", with { getJSONObject("keyBar").put("ctrlC", "hidden") }.field)
        assertEquals("keyBar.always", with { getJSONObject("keyBar").put("always", "yes") }.field)
        assertEquals("keyBar.groups[1].id", with { getJSONObject("keyBar").getJSONArray("groups").getJSONObject(0).put("id", "Not An Id") }.field)
        assertEquals("keyBar.order[1]", with { getJSONObject("keyBar").put("order", JSONArray().put(5)) }.field)
        assertEquals("gestures.tap", with { getJSONObject("gestures").put("tap", "spin") }.field)
        assertEquals("gestures.doubleTapMs", with { getJSONObject("gestures").put("doubleTapMs", 5) }.field)
        assertEquals("security.lockAfterSeconds", with { getJSONObject("security").put("lockAfterSeconds", 42) }.field)
        assertEquals("security.lock", with { getJSONObject("security").put("lock", 1) }.field)
        assertEquals("terminal.font", with { getJSONObject("terminal").put("font", "../../etc") }.field)
        with { getJSONObject("hosts").getJSONArray("hosts").getJSONObject(0).put("tryOthers", "no") }.let {
            assertEquals(SettingsProblem.Kind.Hosts, it.kind)
            assertEquals(ImportProblem.Kind.BadOption, it.hosts!!.kind)
            assertEquals("tryOthers", it.hosts!!.field)
        }
        with { getJSONObject("hosts").getJSONArray("hosts").getJSONObject(0).put("otherUris", JSONArray().put("ssh://me:pw@x.example.com")) }.let {
            assertEquals(ImportProblem.Kind.UriHasPassword, it.hosts!!.kind)
            assertEquals("otherUris", it.hosts!!.field)
        }
    }

    @Test
    fun anExportSaysWhyEachHostWasLeftOut() {
        val hosts = listOf(
            ExportedHost("agents", ImportAddress("me", "a.example.com", 22, true), emptyList(), null, null),
            ExportedHost("Agents", ImportAddress("me", "b.example.com", 22, true), emptyList(), null, null),
            ExportedHost("My Mac", ImportAddress("me", "mac.example.com", 22, true), emptyList(), null, null),
            ExportedHost("desk", ImportAddress(null, "desk.local", 7297, true), emptyList(), null, null),
        )
        val list = HostListFormat.write(hosts)
        assertEquals(listOf("agents"), list.written)
        assertEquals(listOf("Agents"), list.leftFor(HostListExport.Reason.SameName))
        assertEquals(listOf("My Mac"), list.leftFor(HostListExport.Reason.BadName))
        assertEquals(listOf("desk"), list.leftFor(HostListExport.Reason.NotSsh))
        // A backup takes Rex ports and Rex's names: only the second "agents" is left out, and says so.
        val backup = SettingsFormat.write(SettingsSnapshot(hosts = hosts))
        assertEquals(listOf("agents", "My Mac", "desk"), backup.hosts)
        assertEquals(listOf("Agents"), backup.leftFor(HostListExport.Reason.SameName))
    }

    @Test
    fun theAccountIsAGenericNameAndNoSecretOfTheTailnetGoesIn() {
        val text = SettingsFormat.write(snapshot).text
        assertTrue(text.contains("\"tailnetAccount\": \"Work\""))
        for (word in listOf("authKey", "setupKey", "nodeKey", "management", "PRIVATE")) assertFalse(word, text.contains(word, ignoreCase = true))
    }
}
