package me.leaver.rexpocket.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings backup's tailnet parts: each account's choices and the tiles round-trip, an older
 * file without them still loads and changes nothing of them, a backend this build lacks is left
 * out, bad values are refused with where, and the preview lists what changes. Never a sign-in.
 */
class TailnetBackupTest {
    private val agents = ExportedHost("agents", ImportAddress(null, "agents.example.ts.net", 7297, plaintext = true), emptyList(), null, null)
    private val work = TailnetChoices("tailscale", "Work", first = false, TailnetPolicy(TailnetUse.WhenNeeded, lingerMs = null, keep = BackgroundKeep.Always, notify = false))
    private val first = TailnetChoices("tailscale", "Tailscale", first = true, TailnetPolicy(lingerMs = 60_000L))
    private val tiles = listOf(TileBinding(1, tailnet = "tailscale"), TileBinding(3, host = "agents"))
    private val snapshot = SettingsSnapshot(hosts = listOf(agents), tailnets = listOf(first, work), tiles = tiles)

    private fun parsed(text: String): SettingsBackup = when (val p = SettingsFormat.parse(text)) {
        is SettingsParse.Parsed -> p.backup
        is SettingsParse.Failed -> throw AssertionError("refused: ${p.problem}")
    }

    private fun problem(text: String): SettingsProblem = when (val p = SettingsFormat.parse(text)) {
        is SettingsParse.Parsed -> throw AssertionError("expected a refusal")
        is SettingsParse.Failed -> p.problem
    }

    private fun file(edit: JSONObject.() -> Unit): String = JSONObject(SettingsFormat.write(snapshot).text).apply(edit).toString()

    @Test
    fun accountsAndTilesRoundTrip() {
        val text = SettingsFormat.write(snapshot).text
        val back = parsed(text)
        assertEquals(listOf(first, work), back.tailnets)
        assertEquals(tiles, back.tiles)
        // Names and choices only.
        for (word in listOf("authKey", "setupKey", "login", "nodeKey", "off")) assertFalse(word, text.contains("\"$word\""))
    }

    @Test
    fun anOlderFileWithoutThemLoadsAndLeavesThemAlone() {
        val older = file {
            remove("tailnets")
            remove("tiles")
        }
        val back = parsed(older)
        assertNull(back.tailnets)
        assertNull(back.tiles)
        val current = CurrentSettings(tailnets = listOf(first), tiles = listOf(TileBinding(2, host = "agents")))
        val plan = (ImportPlan.of(HostImportParse.Parsed(back.hosts), emptyList()) as HostImportPlan.Planned).plan
        assertTrue(settingChanges(back, current, plan).none { it.key == SettingKey.TailnetChoices || it.key == SettingKey.Tile })
    }

    @Test
    fun aBackendThisBuildLacksIsLeftOut() {
        val text = file {
            getJSONObject("tailnets").getJSONArray("accounts").put(JSONObject().put("backend", "elsewhere").put("name", "Lab"))
            getJSONArray("tiles").put(JSONObject().put("slot", 2).put("tailnet", "elsewhere"))
        }
        val back = parsed(text)
        assertEquals(listOf(first, work), back.tailnets)
        assertEquals(tiles, back.tiles)
    }

    @Test
    fun badValuesAreRefusedWithWhere() {
        fun account(edit: JSONObject.() -> Unit) = file { getJSONObject("tailnets").getJSONArray("accounts").getJSONObject(1).edit() }
        assertEquals("tailnets.accounts[2].use", problem(account { put("use", "sometimes") }).field)
        assertEquals("tailnets.accounts[2].stopAfterMs", problem(account { put("stopAfterMs", 7) }).field)
        assertEquals("tailnets.accounts[2].keep", problem(account { put("keep", "forever") }).field)
        assertEquals("tailnets.accounts[2].name", problem(account { put("name", " ") }).field)
        val twice = file { getJSONObject("tailnets").getJSONArray("accounts").put(JSONObject().put("backend", "tailscale").put("name", " WORK")) }
        assertEquals("tailnets.accounts[3].name", problem(twice).field)
        fun tile(edit: JSONObject.() -> Unit) = file { getJSONArray("tiles").getJSONObject(1).edit() }
        assertEquals("tiles[2].slot", problem(tile { put("slot", 4) }).field)
        assertEquals(SettingsProblem.Kind.UnknownHost, problem(tile { put("host", "nobody") }).kind)
        assertEquals("tiles[2]", problem(tile { put("tailnet", "tailscale") }).field)
        assertEquals("tiles[2].slot", problem(tile { put("slot", 1) }).field)
    }

    @Test
    fun thePreviewListsChangedChoicesNewAccountsAndTiles() {
        val back = parsed(SettingsFormat.write(snapshot).text)
        val plan = (ImportPlan.of(HostImportParse.Parsed(back.hosts), emptyList()) as HostImportPlan.Planned).plan
        // The phone has its first account at the defaults, no Work, and tile 1 on the same tailnet.
        val current = CurrentSettings(
            tailnets = listOf(TailnetChoices("tailscale", "Home", first = true, TailnetPolicy())),
            tiles = listOf(TileBinding(1, tailnet = "tailscale")),
        )
        val changes = settingChanges(back, current, plan)
        val tailnet = changes.filter { it.key == SettingKey.TailnetChoices }
        assertEquals(listOf("Tailscale", "Tailscale · Work"), tailnet.map { it.host })
        assertEquals(TailnetPolicy(), tailnet[0].old)
        assertNull("a new account", tailnet[1].old)
        val tile = changes.filter { it.key == SettingKey.Tile }
        assertEquals(listOf("3"), tile.map { it.host })
    }
}
