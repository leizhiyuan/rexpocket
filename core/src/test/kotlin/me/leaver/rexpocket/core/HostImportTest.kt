package me.leaver.rexpocket.core

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class HostImportTest {
    private val fpA = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
    private val fpB = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 2 })

    private fun host(
        name: String = "agents",
        uri: String? = "ssh://me@100.92.1.2:22",
        dnsUri: String? = "ssh://me@agents.example.ts.net:22",
        fingerprint: String? = fpA,
        comment: String? = "provision:owner-phone",
    ): JSONObject = JSONObject().put("name", name)
        .put("uri", uri ?: JSONObject.NULL)
        .put("dnsUri", dnsUri ?: JSONObject.NULL)
        .put("hostKeyFingerprint", fingerprint ?: JSONObject.NULL)
        .put("keyComment", comment ?: JSONObject.NULL)

    private fun file(vararg hosts: JSONObject, format: Any? = HostListFormat.FORMAT): String {
        val root = JSONObject().put("note", "made for a test").put("hosts", JSONArray(hosts.toList()))
        if (format != null) root.put("format", format)
        return root.toString()
    }

    private fun parsed(text: String): HostImportParse.Parsed =
        HostListFormat.parse(text) as? HostImportParse.Parsed ?: error("did not parse: ${HostListFormat.parse(text)}")

    private fun problem(text: String): ImportProblem =
        (HostListFormat.parse(text) as? HostImportParse.Failed)?.problem ?: error("parsed, but should not have")

    // The parser.

    @Test
    fun aValidFileReadsEveryHostAndPrefersTheDnsName() {
        val hosts = parsed(file(host(), host(name = "build-2", uri = null, fingerprint = null, comment = null).put("extra", 1))).hosts
        assertEquals(2, hosts.size)
        val first = hosts[0]
        assertEquals("agents", first.name)
        assertEquals(ImportAddress("me", "agents.example.ts.net", 22, true), first.address)
        assertEquals(listOf(ImportAddress("me", "100.92.1.2", 22, true)), first.fallbacks)
        assertEquals(fpA, first.fingerprint)
        assertEquals("provision:owner-phone", first.keyComment)
        assertNull(hosts[1].fingerprint)
        assertTrue(hosts[1].fallbacks.isEmpty())
        assertEquals("rexpocket:build-2", hosts[1].commentForKey)
    }

    @Test
    fun aShareIsTakenForAHostListWhenItNamesTheFormat() {
        assertTrue(HostListFormat.mentionsFormat(file(host())))
        assertTrue(HostListFormat.mentionsFormat("""{"format":"rexpocket-hosts\/1"}"""))
        assertFalse(HostListFormat.mentionsFormat("""{"some":"json"}"""))
        assertEquals(1, parsed("""{"format":"rexpocket-hosts\/1","hosts":[{"name":"a","dnsUri":"ssh:\/\/me@a.example.com"}]}""").hosts.size)
    }

    @Test
    fun aHostWithOnlyAnAddressUsesIt() {
        val h = parsed(file(host(dnsUri = null))).hosts.single()
        assertEquals("100.92.1.2", h.address.host)
        assertTrue(h.fallbacks.isEmpty())
    }

    @Test
    fun anyOtherFormatIsRefused() {
        assertEquals(ImportProblem.Kind.WrongFormat, problem(file(host(), format = "rexpocket-hosts/2")).kind)
        assertEquals(ImportProblem.Kind.WrongFormat, problem(file(host(), format = "fleet-rexpocket-hosts/1")).kind)
        assertEquals(ImportProblem.Kind.WrongFormat, problem(file(host(), format = 1)).kind)
        assertEquals(ImportProblem.Kind.MissingFormat, problem(file(host(), format = null)).kind)
        assertEquals(ImportProblem.Kind.NotJson, problem("not json").kind)
        assertEquals(ImportProblem.Kind.NotJson, problem("[1,2]").kind)
        assertEquals(ImportProblem.Kind.NoHosts, problem(file()).kind)
    }

    @Test
    fun aFileOver64KiBOrOver50HostsIsRefused() {
        val big = file(host().put("note", "x".repeat(HostListFormat.MAX_BYTES)))
        assertEquals(ImportProblem.Kind.TooLarge, problem(big).kind)
        assertEquals(ImportProblem.Kind.TooLarge, (HostListFormat.parse(big.toByteArray()) as HostImportParse.Failed).problem.kind)
        val many = file(*Array(51) { host(name = "h$it") })
        assertEquals(ImportProblem.Kind.TooManyHosts, problem(many).kind)
        assertEquals(50, parsed(file(*Array(50) { host(name = "h$it") })).hosts.size)
    }

    @Test
    fun aBadNameIsRefusedAndNamesTheEntry() {
        for (bad in listOf("", "has space", "x".repeat(65), "semi;colon", "ünï")) {
            val p = problem(file(host(name = "ok"), host(name = bad)))
            assertEquals(bad, ImportProblem.Kind.BadName, p.kind)
            assertEquals(2, p.entry)
        }
        assertEquals(ImportProblem.Kind.BadName, problem(file(host().put("name", 3))).kind)
        assertEquals(ImportProblem.Kind.DuplicateName, problem(file(host(name = "a"), host(name = "A"))).kind)
        assertEquals(1, parsed(file(host(name = "x".repeat(64)))).hosts.size)
        assertEquals(1, parsed(file(host(name = "a.b_c-9"))).hosts.size)
    }

    @Test
    fun onlySshUrisAreTaken() {
        fun kind(uri: String) = problem(file(host(name = "agents", dnsUri = uri))).also {
            assertEquals("agents", it.name)
            assertEquals("dnsUri", it.field)
        }.kind
        assertEquals(ImportProblem.Kind.NotSsh, kind("https://me@agents.example.com"))
        assertEquals(ImportProblem.Kind.NotSsh, kind("ws://agents.example.com:7297"))
        assertEquals(ImportProblem.Kind.BadUri, kind("agents.example.com"))
        assertEquals(ImportProblem.Kind.UriHasPassword, kind("ssh://me:secret@agents.example.com"))
        assertEquals(ImportProblem.Kind.UriHasPath, kind("ssh://me@agents.example.com/home"))
        assertEquals(ImportProblem.Kind.UriHasPath, kind("ssh://me@agents.example.com:22/"))
        assertEquals(ImportProblem.Kind.UriHasQuery, kind("ssh://me@agents.example.com?x=1"))
        assertEquals(ImportProblem.Kind.UriNoUser, kind("ssh://agents.example.com"))
        assertEquals(ImportProblem.Kind.BadPort, kind("ssh://me@agents.example.com:0"))
        assertEquals(ImportProblem.Kind.BadPort, kind("ssh://me@agents.example.com:65536"))
        assertEquals(ImportProblem.Kind.BadUri, kind("ssh://me@"))
        assertEquals(2222, parsed(file(host(dnsUri = "ssh://me@agents.example.com:2222"))).hosts.single().address.port)
        assertEquals(22, parsed(file(host(dnsUri = "ssh://me@[fd00::1]"))).hosts.single().address.port)
        // The same rules for uri.
        assertEquals("uri", problem(file(host(uri = "ssh://me:pw@1.2.3.4"))).field)
    }

    @Test
    fun aFingerprintMustBeWhatTheAppShows() {
        for (bad in listOf("SHA256:abc", "$fpA=", "sha256:" + fpA.removePrefix("SHA256:"), "MD5:" + fpA.removePrefix("SHA256:"), fpA.dropLast(1) + "!", fpA + "A")) {
            assertEquals(bad, ImportProblem.Kind.BadFingerprint, problem(file(host(fingerprint = bad))).kind)
        }
        // The last character carries 4 bits of padding, which must be zero for the text to be canonical.
        assertFalse(HostListFormat.validFingerprint(fpA.dropLast(1) + "B"))
        assertTrue(HostListFormat.validFingerprint(SshFingerprint.of(byteArrayOf(1, 2, 3))))
        assertEquals(ImportProblem.Kind.BadFingerprint, problem(file(host().put("hostKeyFingerprint", 5))).kind)
    }

    @Test
    fun aHostWithNeitherUriIsRefused() {
        assertEquals(ImportProblem.Kind.NoAddress, problem(file(host(uri = null, dnsUri = null))).kind)
        assertEquals(ImportProblem.Kind.NoAddress, problem(file(JSONObject().put("name", "agents"))).kind)
    }

    @Test
    fun aKeyCommentIsOneShortLine() {
        assertEquals(ImportProblem.Kind.BadKeyComment, problem(file(host(comment = "a\nb"))).kind)
        assertEquals(ImportProblem.Kind.BadKeyComment, problem(file(host(comment = "x".repeat(257)))).kind)
    }

    @Test
    fun oneBadEntryFailsTheWholeFile() {
        val text = file(host(name = "good-1"), host(name = "good-2"), host(name = "bad", dnsUri = "ssh://me:pw@x"), host(name = "good-3"))
        val p = problem(text)
        assertEquals(3, p.entry)
        assertEquals("bad", p.name)
        assertEquals(ImportProblem.Kind.UriHasPassword, p.kind)
        assertFalse(p.toString().contains("pw"))
    }

    // The plan.

    private fun known(
        id: String,
        name: String,
        address: String,
        hostKey: String? = null,
        fallbacks: List<String> = emptyList(),
        credentials: Boolean = true,
    ) = KnownHost(id, name, ImportAddress.parse(address)!!, fallbacks.map { ImportAddress.parse(it)!! }, hostKey, credentials)

    private fun plan(text: String, vararg known: KnownHost): ImportPlan =
        (ImportPlan.of(parsed(text), known.toList()) as HostImportPlan.Planned).plan

    @Test
    fun thePreviewSaysNewUnchangedOrChanged() {
        val p = plan(
            file(
                host(name = "fresh", dnsUri = "ssh://me@fresh.example.com:22", uri = null),
                host(name = "same"),
                host(name = "moved", dnsUri = "ssh://me@moved.example.ts.net:2222"),
                host(name = "keyed", fingerprint = fpB),
            ),
            known("1", "same", "ssh://me@agents.example.ts.net:22", fpA, listOf("ssh://me@100.92.1.2:22")),
            known("2", "moved", "ssh://me@moved.example.ts.net:22", fpA, listOf("ssh://me@100.92.1.2:22")),
            known("3", "keyed", "ssh://me@agents.example.ts.net:22", null, listOf("ssh://me@100.92.1.2:22")),
        )
        val (fresh, same, moved, keyed) = p.hosts
        assertEquals(ImportStatus.New, fresh.status)
        assertEquals(KeyChange.Set(fpA), fresh.key)
        assertEquals(ImportStatus.Unchanged, same.status)
        assertEquals(KeyChange.Same, same.key)
        assertEquals(ImportStatus.Changed, moved.status)
        assertEquals(
            listOf(FieldChange(ImportField.Address, "ssh://me@moved.example.ts.net:22", "ssh://me@moved.example.ts.net:2222")),
            moved.changes,
        )
        assertEquals(ImportStatus.Changed, keyed.status)
        assertTrue(keyed.changes.isEmpty())
        assertEquals(KeyChange.Set(fpB), keyed.key)
        assertFalse(keyed.keyConflict)
    }

    @Test
    fun aHostIsMatchedByNameThenByAddress() {
        val p = plan(
            file(host(name = "Renamed"), host(name = "other", dnsUri = "ssh://me@other.example.com:22", uri = null)),
            known("1", "old-name", "ssh://me@100.92.1.2:22"),
            known("2", "OTHER", "ssh://someone@else.example.com:22"),
        )
        val (renamed, other) = p.hosts
        assertEquals("1", renamed.existing?.id)
        assertTrue(renamed.changes.any { it.field == ImportField.Name && it.old == "old-name" && it.new == "Renamed" })
        assertEquals("2", other.existing?.id)
        assertTrue(other.changes.any { it.field == ImportField.Address })
    }

    @Test
    fun aMatchThatIsNotOneHostFailsThePlan() {
        val twoByAddress = ImportPlan.of(
            parsed(file(host(name = "x"))),
            listOf(known("1", "a", "ssh://me@100.92.1.2:22"), known("2", "b", "ssh://me@agents.example.ts.net:22")),
        )
        assertEquals(ImportProblem.Kind.AmbiguousMatch, (twoByAddress as HostImportPlan.Failed).problem.kind)
        val oneHostTwice = ImportPlan.of(
            parsed(file(host(name = "x", dnsUri = null), host(name = "y", dnsUri = null))),
            listOf(known("1", "a", "ssh://me@100.92.1.2:22")),
        )
        assertEquals(ImportProblem.Kind.AmbiguousMatch, (oneHostTwice as HostImportPlan.Failed).problem.kind)
    }

    @Test
    fun aDifferentPinnedKeyIsReplacedOnlyWhenThatHostIsConfirmed() {
        val p = plan(
            file(host(name = "a", fingerprint = fpB), host(name = "b", fingerprint = fpB, dnsUri = "ssh://me@b.example.com:22", uri = null)),
            known("1", "a", "ssh://me@agents.example.ts.net:22", fpA, listOf("ssh://me@100.92.1.2:22")),
            known("2", "b", "ssh://me@b.example.com:22", fpA),
        )
        assertEquals(listOf("a", "b"), p.conflicts.map { it.imported.name })
        assertEquals(KeyChange.Replaced(fpA, fpB), p.hosts[0].key)

        val none = p.confirm(emptySet())
        assertTrue(none.updates.isEmpty())
        assertEquals(listOf("a", "b"), none.held.map { it.imported.name })

        val one = p.confirm(setOf("b"))
        assertEquals(listOf("b"), one.updates.map { it.imported.name })
        assertEquals(fpB, one.updates.single().pinnedKey(fpA))
        assertEquals(listOf("a"), one.held.map { it.imported.name })
    }

    @Test
    fun noFingerprintKeepsWhatIsPinnedOrTheFirstConnectionsPrompt() {
        val p = plan(
            file(host(name = "a", fingerprint = null), host(name = "b", fingerprint = null, dnsUri = "ssh://me@b.example.com:22", uri = null)),
            known("1", "a", "ssh://me@agents.example.ts.net:22", fpA, listOf("ssh://me@100.92.1.2:22")),
        )
        assertEquals(KeyChange.Kept, p.hosts[0].key)
        assertEquals(fpA, p.hosts[0].pinnedKey(fpA))
        assertEquals(ImportStatus.Unchanged, p.hosts[0].status)
        assertEquals(KeyChange.Kept, p.hosts[1].key)
        assertEquals("", p.hosts[1].pinnedKey(""))
    }

    /** A stand-in for the app's saved host. */
    private data class Saved(val id: String, val name: String, val address: String, val hostKey: String, val privateKey: String)

    @Test
    fun anImportNeverRemovesAHostAndGivesEachNewOneAFreshKey() {
        val existing = listOf(
            Saved("1", "kept", "ssh://me@kept.example.com:22", fpA, "existing-private-key"),
            Saved("2", "agents", "ssh://me@agents.example.ts.net:22", "", "agents-private-key"),
        )
        val p = plan(
            file(host(name = "agents"), host(name = "new-1", dnsUri = "ssh://me@n1.example.com:22", uri = null), host(name = "new-2", dnsUri = "ssh://me@n2.example.com:22", uri = null, comment = null)),
            *existing.map { known(it.id, it.name, it.address, it.hostKey.ifEmpty { null }) }.toTypedArray(),
        )
        val result = p.confirm(emptySet())
        assertEquals(listOf("new-1", "new-2"), result.creates.map { it.imported.name })
        assertTrue(result.creates.all { it.needsKey })
        assertFalse(result.updates.single().needsKey)
        val keys = result.creates.associate { it.imported.name to SshKeys.generate(it.imported.commentForKey) }
        val after = result.applyTo(
            existing,
            id = { it.id },
            update = { h, u -> h.copy(hostKey = u.pinnedKey(h.hostKey)) },
            create = { c -> Saved("n-" + c.imported.name, c.imported.name, c.imported.address.toString(), c.pinnedKey(""), keys.getValue(c.imported.name).privateKey) },
        )
        assertEquals(listOf("kept", "agents", "new-1", "new-2"), after.map { it.name })
        assertEquals(existing[0], after[0])
        assertEquals(fpA, after[1].hostKey)
        assertEquals("agents-private-key", after[1].privateKey)
        val privates = after.map { it.privateKey }
        assertEquals(privates.size, privates.toSet().size)
        assertNotEquals(keys["new-1"]!!.publicKey, keys["new-2"]!!.publicKey)
        assertTrue(keys["new-1"]!!.publicKey.endsWith(" provision:owner-phone"))
        assertTrue(keys["new-2"]!!.publicKey.endsWith(" rexpocket:new-2"))
        assertEquals(fpA, after[2].hostKey)
        assertFalse(result.summary().contains("PRIVATE"))
    }

    @Test
    fun anSshHostWithoutCredentialsGetsAFreshKeyToo() {
        val p = plan(
            file(host(name = "bare"), host(name = "has-key", dnsUri = "ssh://me@k.example.com:22", uri = null)),
            known("1", "bare", "ssh://me@agents.example.ts.net:22", credentials = false),
            known("2", "has-key", "ssh://me@k.example.com:22"),
            known("3", "unrelated", "ws://desk.example.com:7297"),
        )
        assertTrue(p.hosts[0].needsKey)
        assertFalse(p.hosts[1].needsKey)
    }

    // Keys.

    @Test
    fun aGeneratedKeyIsOneJSchReadsAndSignsWith() {
        configureJsch()
        val key = SshKeys.generate("provision:owner-phone")
        assertTrue(key.privateKey.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
        assertTrue(key.publicKey.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI"))
        val jsch = JSch()
        val pair = KeyPair.load(jsch, key.privateKey.toByteArray(), null)
        assertFalse(pair.isEncrypted)
        assertEquals(key.publicKey.split(' ')[1], Base64.getEncoder().encodeToString(pair.publicKeyBlob))
        assertEquals(SshFingerprint.of(pair.publicKeyBlob), key.fingerprint)
        assertTrue(pair.getSignature("hello".toByteArray(), "ssh-ed25519").isNotEmpty())
        jsch.addIdentity("test", key.privateKey.toByteArray(), null, null)
        assertEquals("provision:owner-phone", SshKeys.commentOf(key.privateKey))
        assertFalse(key.toString().contains("PRIVATE"))
        assertNotEquals(key.privateKey, SshKeys.generate("provision:owner-phone").privateKey)
    }

    @Test
    fun thePublicKeysGoOutAsAuthorizedKeysAndAsJson() {
        val a = SshKeys.generate("c")
        val entries = listOf(PublicKeyList.Entry("one", a.publicKey), PublicKeyList.Entry("two", SshKeys.generate("c").publicKey))
        val text = PublicKeyList.authorizedKeys(entries)
        assertEquals(listOf("# one", a.publicKey), text.lines().take(2))
        assertEquals(4, text.trim().lines().size)
        val json = JSONObject(PublicKeyList.json(entries))
        assertEquals("rexpocket-public-keys/1", json.getString("format"))
        assertEquals("one", json.getJSONArray("hosts").getJSONObject(0).getString("name"))
        assertEquals(a.publicKey, json.getJSONArray("hosts").getJSONObject(0).getString("publicKey"))
        assertFalse(text.contains("PRIVATE") || json.toString().contains("PRIVATE"))
    }

    // Export, and Rex's host list.

    @Test
    fun anExportReadsBackAsTheSameHosts() {
        val export = HostListFormat.write(
            listOf(
                ExportedHost("agents", ImportAddress("me", "agents.example.ts.net", 22, true), listOf(ImportAddress("me", "100.92.1.2", 22, true)), fpA, "c"),
                ExportedHost("My Mac", ImportAddress("me", "mac.example.com", 22, true), emptyList(), null, null),
                ExportedHost("rex-port", ImportAddress(null, "desk.example.com", 7297, true), emptyList(), null, null),
            ),
        )
        assertEquals(listOf("agents"), export.written)
        assertEquals(listOf("My Mac", "rex-port"), export.left)
        val back = parsed(export.text).hosts.single()
        assertEquals("agents", back.name)
        assertEquals("agents.example.ts.net", back.address.host)
        assertEquals("100.92.1.2", back.fallbacks.single().host)
        assertEquals(fpA, back.fingerprint)
        assertEquals("c", back.keyComment)
    }

    @Test
    fun rexsHostListBecomesHostsWithTheEndpointsThePhoneCanUse() {
        val payload = JSONObject(
            """
            {"hash":"h","hosts":[
              {"id":"host:1","label":"studio","endpoints":["studio.example.ts.net","192.168.1.20:7297","unix:///tmp/rex.sock"],"added_from":"laptop"},
              {"id":"host:2","label":"socket only","endpoints":["unix:///tmp/rex.sock"]},
              {"id":"host:3","label":"over ssh","endpoints":["ssh://me@vm.example.com:2222","ssh://nouser.example.com"],"icon":"server"}
            ]}
            """.trimIndent(),
        )
        val read = RexHostList.read(payload) as HostImportParse.Parsed
        assertEquals(listOf("studio", "over ssh"), read.hosts.map { it.name })
        assertEquals(listOf(SkippedHost("socket only", SkippedHost.Reason.NoUsableEndpoint)), read.skipped)
        val studio = read.hosts[0]
        assertEquals(ImportAddress(null, "studio.example.ts.net", 443, false), studio.address)
        assertEquals(listOf(ImportAddress(null, "192.168.1.20", 7297, true)), studio.fallbacks)
        assertNull(studio.fingerprint)
        val ssh = read.hosts[1]
        assertEquals(ImportAddress("me", "vm.example.com", 2222, true), ssh.address)
        assertTrue(ssh.fallbacks.isEmpty())

        val p = (ImportPlan.of(read, listOf(known("1", "studio", "wss://studio.example.ts.net:443"))) as HostImportPlan.Planned).plan
        assertEquals(ImportStatus.Changed, p.hosts[0].status)
        assertFalse(p.hosts[0].needsKey)
        assertEquals(ImportStatus.New, p.hosts[1].status)
        assertTrue(p.hosts[1].needsKey)
        assertEquals(KeyChange.Kept, p.hosts[1].key)
        assertEquals(1, p.skipped.size)

        assertTrue((RexHostList.read(JSONObject().put("hosts", JSONObject.NULL).put("hash", "h")) as HostImportParse.Parsed).hosts.isEmpty())
    }
}
