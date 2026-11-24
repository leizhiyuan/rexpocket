package me.leaver.rexpocket.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Settings › Backup: the `rexpocket-settings/1` file written and read back, its checks (all or
 * nothing), the sealed SSH credentials, and what an import would change. See
 * docs/design/settings-backup.md.
 */
class SettingsBackupTest {
    private val fpA = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
    private val fast = BackupCrypto.MIN_ITERATIONS

    private val ssh = ExportedHost(
        "agents", ImportAddress("me", "agents.example.com", 22, true),
        listOf(ImportAddress("me", "100.92.1.2", 22, true)), fpA, "rexpocket:agents",
    )
    private val rexPort = ExportedHost("Desk mac", ImportAddress(null, "desk.local", 7297, plaintext = true), emptyList(), null, null)
    private val tls = ExportedHost("studio", ImportAddress(null, "studio.example.ts.net", 443, plaintext = false), emptyList(), null, null)

    private val key = SshKeys.generate("rexpocket:agents")

    private val snapshot = SettingsSnapshot(
        appearance = Appearance(AppTheme.Dark, TerminalColors.Light, TextSize.Larger),
        terminalFontScale = 1.25f,
        notifications = AlertPrefs(waiting = true, failed = false, done = true, messages = false, bells = true, vibrate = false, progress = true),
        backgroundMode = true,
        home = HomePrefs("Activity", reopenLastTab = true, activityBySession = true, includeIdle = true, hostOrder = listOf("studio", "agents", "Desk mac"), folded = setOf("Desk mac"), unfolded = setOf("agents")),
        hosts = listOf(ssh, rexPort, tls),
        hostBackground = mapOf(
            "agents" to HostBackground(stay = true, always = true, notify = true, overrides = mapOf(AlertEvent.Bell to true)),
            "Desk mac" to HostBackground(stay = false),
        ),
        quickReplies = listOf("yes", "/compact", "Run the tests,\nthen commit"),
        secrets = listOf(HostSecret("agents", password = null, privateKey = key.privateKey, passphrase = null)),
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
    fun everySettingRoundTrips() {
        val export = SettingsFormat.write(snapshot)
        assertEquals(listOf("agents", "Desk mac", "studio"), export.hosts)
        assertEquals(0, export.sealedHosts)
        val b = parsed(export.text)
        assertEquals(snapshot.appearance, b.appearance)
        assertEquals(1.25f, b.terminalFontScale!!, 0.0001f)
        assertEquals(snapshot.notifications, b.notifications)
        assertEquals(true, b.backgroundMode)
        assertEquals(snapshot.home!!.copy(), b.home)
        assertEquals(snapshot.hostBackground, b.hostBackground)
        assertEquals(snapshot.quickReplies, b.quickReplies)
        assertEquals(listOf("agents", "Desk mac", "studio"), b.hosts.map { it.name })
        val agents = b.hosts[0]
        assertEquals(ssh.address, agents.address)
        assertEquals(ssh.fallbacks, agents.fallbacks)
        assertEquals(fpA, agents.fingerprint)
        assertEquals("rexpocket:agents", agents.keyComment)
        assertEquals(rexPort.address, b.hosts[1].address)
        assertTrue(b.hosts[2].address.sameAs(tls.address))
        assertNull(b.secrets)
    }

    /**
     * The Activity view's settings are two new fields of `home`, read when there and left as they
     * are when not: a file from before them still imports, and changes nothing of them.
     */
    @Test
    fun theActivitySettingsAreOptionalFieldsOfHome() {
        val root = JSONObject(SettingsFormat.write(snapshot).text)
        val home = root.getJSONObject("home")
        assertEquals(true, home.getBoolean("activityBySession"))
        assertEquals(true, home.getBoolean("includeIdle"))
        assertEquals("Activity", home.getString("filter"))
        home.remove("activityBySession")
        home.remove("includeIdle")
        val older = parsed(root.toString())
        assertNull(older.home!!.activityBySession)
        assertNull(older.home!!.includeIdle)
        val changes = settingChanges(older, CurrentSettings(activityBySession = true), ImportPlan(emptyList()))
        assertTrue(changes.none { it.key == SettingKey.ActivityBySession || it.key == SettingKey.IncludeIdle })
        val both = settingChanges(parsed(SettingsFormat.write(snapshot).text), CurrentSettings(), ImportPlan(emptyList()))
        assertTrue(both.any { it.key == SettingKey.ActivityBySession && it.new == true })
        assertTrue(both.any { it.key == SettingKey.IncludeIdle && it.new == true })
        home.put("includeIdle", "yes")
        assertEquals(SettingsProblem.Kind.BadValue, problem(root.toString()).kind)
    }

    @Test
    fun theHostsAreAHostListInsideTheFile() {
        val root = JSONObject(SettingsFormat.write(snapshot.copy(hosts = listOf(ssh))).text)
        val hosts = root.getJSONObject("hosts")
        assertEquals(HostListFormat.FORMAT, hosts.getString("format"))
        // Its SSH hosts are a list Import hosts reads as it is.
        val alone = HostListFormat.parse(hosts.toString()) as HostImportParse.Parsed
        assertEquals(listOf("agents"), alone.hosts.map { it.name })
        // Rex's own port is the backup's alone: a plain host list still refuses it.
        val wide = JSONObject(SettingsFormat.write(snapshot).text).getJSONObject("hosts")
        assertTrue(HostListFormat.parse(wide.toString()) is HostImportParse.Failed)
    }

    @Test
    fun noSecretGoesInUnlessAskedAndThenOnlySealed() {
        val plain = SettingsFormat.write(snapshot).text
        assertFalse(plain.contains("PRIVATE KEY"))
        assertFalse(plain.contains("secrets"))
        // There is no tailnet field to fill: the node's key and login never leave the phone.
        assertFalse(plain.contains("tailnet", ignoreCase = true))
        assertFalse(plain.contains("authKey", ignoreCase = true))

        val sealed = SettingsFormat.write(snapshot, passphrase = "correct horse battery", iterations = fast)
        assertEquals(1, sealed.sealedHosts)
        assertFalse(sealed.text.contains("PRIVATE KEY"))
        assertFalse(sealed.text.contains(key.privateKey.lines()[1]))
        val box = JSONObject(sealed.text).getJSONObject("secrets")
        assertEquals("PBKDF2-HMAC-SHA256", box.getString("kdf"))
        assertEquals("AES-256-GCM", box.getString("cipher"))
        assertEquals(fast, box.getInt("iterations"))
        assertEquals(16, Base64.getDecoder().decode(box.getString("salt")).size)
        assertEquals(12, Base64.getDecoder().decode(box.getString("nonce")).size)
        assertEquals(1, box.getInt("hosts"))
    }

    @Test
    fun theSealedKeysOpenWithThePassphraseOnly() {
        val text = SettingsFormat.write(snapshot, passphrase = "correct horse battery", iterations = fast).text
        val b = parsed(text)
        assertEquals(1, b.secrets!!.hosts)
        val opened = SettingsFormat.open(b, b.secrets!!, "correct horse battery") as OpenedSecrets.Opened
        assertEquals(key.privateKey, opened.hosts.getValue("agents").privateKey)
        assertNull(opened.hosts.getValue("agents").password)
        assertTrue(opened.hosts.toString().contains("agents") && !opened.hosts.toString().contains("PRIVATE"))

        try {
            SettingsFormat.open(b, b.secrets!!, "Correct horse battery")
            fail("a wrong passphrase opened the keys")
        } catch (e: WrongPassphraseException) {
        }

        // One changed byte of the ciphertext: refused the same way.
        val root = JSONObject(text)
        val data = Base64.getDecoder().decode(root.getJSONObject("secrets").getString("data"))
        data[data.size / 2] = (data[data.size / 2].toInt() xor 1).toByte()
        root.getJSONObject("secrets").put("data", Base64.getEncoder().encodeToString(data))
        val tampered = parsed(root.toString())
        try {
            SettingsFormat.open(tampered, tampered.secrets!!, "correct horse battery")
            fail("a changed box opened")
        } catch (e: WrongPassphraseException) {
        }
    }

    @Test
    fun theDefaultRoundsAreUsedAndReadBack() {
        val text = SettingsFormat.write(snapshot, passphrase = "Ünïcödé pass", iterations = BackupCrypto.ITERATIONS).text
        val b = parsed(text)
        assertEquals(600_000, b.secrets!!.box.iterations)
        // NFC: the same words composed another way give the same key.
        val decomposed = java.text.Normalizer.normalize("Ünïcödé pass", java.text.Normalizer.Form.NFD)
        assertTrue(SettingsFormat.open(b, b.secrets!!, decomposed) is OpenedSecrets.Opened)
    }

    @Test
    fun pbkdf2IsTheStandardOne() {
        // RFC 7914, section 11: PBKDF2-HMAC-SHA256 of "passwd" and "salt", one round.
        val vector = BackupCrypto.derive("passwd", "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            vector.joinToString("") { "%02x".format(it) },
        )
        // And the JVM's own, for an ASCII passphrase and a real number of rounds.
        val salt = ByteArray(16) { it.toByte() }
        val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("correct horse".toCharArray(), salt, 10_000, 256)).encoded
        assertArrayEquals(jdk, BackupCrypto.derive("correct horse", salt, 10_000))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val root = JSONObject(SettingsFormat.write(snapshot).text)
        root.put("future", JSONObject().put("x", 1))
        root.getJSONObject("appearance").put("accent", "teal")
        root.getJSONObject("background").getJSONArray("hosts").getJSONObject(0).getJSONObject("events").put("Telepathy", true)
        root.getJSONObject("hosts").getJSONArray("hosts").getJSONObject(0).put("color", "red")
        val b = parsed(root.toString())
        assertEquals(snapshot.appearance, b.appearance)
        assertEquals(snapshot.hostBackground, b.hostBackground)
    }

    @Test
    fun onlyThePartsInTheFileAreRead() {
        val b = parsed(JSONObject().put("format", SettingsFormat.FORMAT).put("appearance", JSONObject().put("app", "dark")).toString())
        assertEquals(Appearance(app = AppTheme.Dark), b.appearance)
        assertNull(b.notifications)
        assertNull(b.home)
        assertTrue(b.hosts.isEmpty())
    }

    @Test
    fun aBadFileIsRefusedWholeAndSaysWhere() {
        assertEquals(SettingsProblem.Kind.NotJson, problem("not json").kind)
        assertEquals(SettingsProblem.Kind.MissingFormat, problem("{}").kind)
        assertEquals(SettingsProblem.Kind.WrongFormat, problem(JSONObject().put("format", "rexpocket-hosts/1").toString()).kind)
        assertEquals(SettingsProblem.Kind.TooLarge, (SettingsFormat.parse(ByteArray(SettingsFormat.MAX_BYTES + 1) { ' '.code.toByte() }) as SettingsParse.Failed).problem.kind)

        fun with(edit: JSONObject.() -> Unit): SettingsProblem = problem(JSONObject(SettingsFormat.write(snapshot).text).apply(edit).toString())
        with { getJSONObject("appearance").put("app", "purple") }.let {
            assertEquals(SettingsProblem.Kind.BadValue, it.kind)
            assertEquals("appearance.app", it.field)
        }
        assertEquals("terminal.fontScale", with { getJSONObject("terminal").put("fontScale", 9) }.field)
        assertEquals("notifications.bells", with { getJSONObject("notifications").put("bells", "yes") }.field)
        assertEquals("home.filter", with { getJSONObject("home").put("filter", "Everything") }.field)
        assertEquals("quickReplies[2]", with { put("quickReplies", JSONArray(listOf("ok", "  "))) }.field)
        assertEquals("quickReplies", with { put("quickReplies", JSONArray(List(QuickReplies.MAX_ITEMS + 1) { "r$it" })) }.field)
        with { getJSONObject("home").put("hostOrder", JSONArray(listOf("agents", "ghost"))) }.let {
            assertEquals(SettingsProblem.Kind.UnknownHost, it.kind)
            assertEquals("ghost", it.detail)
        }
        with { getJSONObject("background").getJSONArray("hosts").getJSONObject(0).put("name", "ghost") }.let {
            assertEquals(SettingsProblem.Kind.UnknownHost, it.kind)
            assertEquals("background.hosts[1].name", it.field)
        }
        // One bad host is the host list's own problem, with its entry.
        with { getJSONObject("hosts").getJSONArray("hosts").getJSONObject(1).put("dnsUri", "ssh://me:secret@x.example.com") }.let {
            assertEquals(SettingsProblem.Kind.Hosts, it.kind)
            assertEquals(ImportProblem.Kind.UriHasPassword, it.hosts!!.kind)
            assertEquals(2, it.hosts!!.entry)
        }
        // A host key on a Rex port is not a host key of anything.
        with { getJSONObject("hosts").getJSONArray("hosts").getJSONObject(1).put("hostKeyFingerprint", fpA) }.let {
            assertEquals(ImportProblem.Kind.BadFingerprint, it.hosts!!.kind)
        }
    }

    @Test
    fun aSealedBoxOutsideTheRulesIsRefused() {
        val root = JSONObject(SettingsFormat.write(snapshot, passphrase = "correct horse battery", iterations = fast).text)
        fun with(edit: JSONObject.() -> Unit) = problem(JSONObject(root.toString()).apply { getJSONObject("secrets").edit() }.toString())
        assertEquals("secrets.iterations", with { put("iterations", 1000) }.field)
        assertEquals("secrets.iterations", with { put("iterations", 50_000_000) }.field)
        assertEquals("secrets.kdf", with { put("kdf", "md5") }.field)
        assertEquals("secrets.nonce", with { put("nonce", "AAAA") }.field)
        assertEquals(SettingsProblem.Kind.BadSecrets, with { remove("data") }.kind)
    }

    /**
     * Launch templates ride with their host's name; a file without them (written before they were)
     * loads; one naming a host it does not have, or a template with nothing to run, is refused; and
     * the same templates under other ids are no change.
     */
    @Test
    fun launchTemplatesRoundTripByHostAndOldFilesLoad() {
        val api = LaunchTemplate("phone-1", "api", "~/src/api", "claude")
        val web = LaunchTemplate("phone-2", "web", "", "npm run dev")
        val text = SettingsFormat.write(snapshot.copy(templates = mapOf("agents" to listOf(api, web), "gone" to listOf(api)))).text
        val b = parsed(text)
        assertEquals(listOf("agents"), b.templates.keys.toList())
        assertEquals(listOf(api, web).map { Triple(it.name, it.directory, it.command) }, b.templates.getValue("agents").map { Triple(it.name, it.directory, it.command) })
        assertTrue("no host's id goes in the file", !text.contains("phone-1"))

        assertEquals(emptyMap<String, List<LaunchTemplate>>(), parsed(SettingsFormat.write(snapshot).text).templates)
        val unknown = JSONObject(text).apply { getJSONArray("templates").getJSONObject(0).put("host", "nobody") }
        assertEquals(SettingsProblem.Kind.UnknownHost, problem(unknown.toString()).kind)
        val empty = JSONObject(text).apply { getJSONArray("templates").getJSONObject(0).put("directory", "").put("command", "") }
        assertEquals("templates[1]", problem(empty.toString()).field)

        val phone = listOf(known("2", "agents", ssh.address, key = fpA, fallbacks = ssh.fallbacks))
        val plan = (ImportPlan.of(HostImportParse.Parsed(b.hosts), phone) as HostImportPlan.Planned).plan
        val same = CurrentSettings(hosts = phone, templates = mapOf("2" to listOf(api.copy(id = "x"), web.copy(id = "y"))))
        assertTrue(settingChanges(b, same, plan).none { it.key == SettingKey.Templates })
        val change = settingChanges(b, CurrentSettings(hosts = phone, templates = mapOf("2" to listOf(web))), plan).single { it.key == SettingKey.Templates }
        assertEquals("agents", change.host)
        assertEquals(listOf("web"), change.old)
        assertEquals(listOf("api", "web"), change.new)
    }

    private fun known(id: String, name: String, address: ImportAddress, key: String? = null, creds: Boolean = true, fallbacks: List<ImportAddress> = emptyList()) =
        KnownHost(id, name, address, fallbacks, key, creds)

    @Test
    fun theChangesAreOnlyWhatDiffersAndTheHostsAreImportHostsOwnPlan() {
        val b = parsed(SettingsFormat.write(snapshot).text)
        val phone = listOf(
            known("2", "agents", ssh.address, key = fpA, creds = false, fallbacks = ssh.fallbacks),
            known("1", "studio", tls.address),
        )
        val current = CurrentSettings(
            appearance = Appearance(AppTheme.Dark),
            notifications = AlertPrefs(),
            hosts = phone,
            hostBackground = mapOf("2" to HostBackground(stay = true, always = true, overrides = mapOf(AlertEvent.Bell to true))),
            folded = setOf("2"),
        )
        val plan = (ImportPlan.of(HostImportParse.Parsed(b.hosts), phone) as HostImportPlan.Planned).plan
        assertEquals(listOf(ImportStatus.Unchanged, ImportStatus.New, ImportStatus.Unchanged), plan.hosts.map { it.status })
        val changes = settingChanges(b, current, plan)
        val keys = changes.map { it.key }
        // The theme is dark already: no change. The agents host's background is the file's already.
        assertFalse(SettingKey.Theme in keys)
        assertTrue(SettingKey.TerminalColors in keys)
        assertTrue(SettingKey.AlertFailed in keys)
        assertEquals(snapshot.quickReplies, changes.single { it.key == SettingKey.QuickReplies }.new)
        assertFalse(SettingKey.AlertWaiting in keys)
        assertEquals(listOf("Desk mac"), changes.filter { it.key == SettingKey.HostBackground }.map { it.host })
        val order = changes.single { it.key == SettingKey.HostOrder }
        assertEquals(listOf("agents", "studio"), order.old)
        assertEquals(listOf("studio", "agents", "Desk mac"), order.new)
        // agents was folded, the file has it open; Desk mac is new and folded.
        assertEquals(listOf("agents", "Desk mac"), changes.single { it.key == SettingKey.HostFolds }.new)
    }

    @Test
    fun theFilesOrderIsLaidOnTheHostsItNamesAndNoneIsDropped() {
        val hosts = listOf("a", "x", "b", "y", "c")
        assertEquals(listOf("c", "x", "a", "y", "b"), inFileOrder(hosts, { it }, listOf("C", "a", "b")))
        assertEquals(hosts, inFileOrder(hosts, { it }, emptyList()))
    }

    @Test
    fun credentialsGoToANewHostOrOneWithNothingToSignInWith() {
        val secret = HostSecret("agents", "pw", null, null)
        val imported = ImportedHost("agents", ssh.address, emptyList(), null, null)
        val fresh = PlannedHost(imported, null, emptyList(), KeyChange.Kept)
        val bare = PlannedHost(imported, known("2", "agents", ssh.address, creds = false), emptyList(), KeyChange.Kept)
        val signed = PlannedHost(imported, known("2", "agents", ssh.address, creds = true), emptyList(), KeyChange.Kept)
        val all = mapOf("agents" to secret)
        assertEquals(secret, credentialsFor(fresh, all))
        assertEquals(secret, credentialsFor(bare, all))
        assertNull(credentialsFor(signed, all))
        assertNull(credentialsFor(fresh, emptyMap()))
    }

    @Test
    fun aBoxWhoseHostsAreNotTheFilesIsRefused() {
        val other = snapshot.copy(secrets = listOf(HostSecret("Desk mac", "pw", null, null)))
        val text = SettingsFormat.write(other, passphrase = "correct horse battery", iterations = fast).text
        val b = parsed(text)
        // A Rex-port host has no SSH sign-in to carry: written as nothing, read as nothing.
        assertEquals(0, b.secrets!!.hosts)
        assertEquals(OpenedSecrets.Opened(emptyMap()), SettingsFormat.open(b, b.secrets!!, "correct horse battery"))
    }
}
