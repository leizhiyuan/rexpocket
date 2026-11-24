package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** A launch template's shell line, and how it reads back from what the phone keeps. */
class LaunchTemplateFormatTest {
    @Test
    fun theLineGoesToTheDirectoryRunsTheCommandAndLeavesAShell() {
        val shell = "exec \"\${SHELL:-sh}\" -l"
        assertEquals("cd ~/'src/api' && claude; $shell", LaunchTemplate("1", "api", "~/src/api", "claude").script())
        assertEquals("cd '/srv/it'\\''s here'; $shell", LaunchTemplate("1", "x", "/srv/it's here", "").script())
        assertEquals("npm run dev | tee log; $shell", LaunchTemplate("1", "x", "", "npm run dev | tee log").script())
        assertEquals("cd ~; $shell", LaunchTemplate("1", "x", "~", "").script())
        assertEquals("cd ~/; $shell", LaunchTemplate("1", "x", "~/", "").script())
        // A `~` that is not the home directory's is quoted like any other character.
        assertEquals("cd '~bob/x'; $shell", LaunchTemplate("1", "x", "~bob/x", "").script())
    }

    @Test
    fun theBlockIsRexsShellWithTheLineAsItsCommand() {
        val block = LaunchTemplate("1", "api", "~/src", "claude").block()
        assertEquals(LaunchTemplate.SHELL_FLAVOR, block.getString("flavor"))
        val options = block.getJSONObject("options")
        val command = options.getJSONArray("command")
        assertEquals(listOf("sh", "-c", "cd ~/'src' && claude; exec \"\${SHELL:-sh}\" -l"), List(command.length()) { command.getString(it) })
        assertTrue("the person's startup files are read, for the PATH their terminals have", options.getBoolean("interactive"))
    }

    @Test
    fun templatesReadBackAndBadOnesAreLeftOut() {
        val list = listOf(LaunchTemplate("a", "api", "~/api", "claude"), LaunchTemplate("b", "web", "", "npm run dev"))
        assertEquals(list, LaunchTemplate.decode(JSONArray(LaunchTemplate.encode(list).toString())))
        val bad = JSONArray()
            .put(org.json.JSONObject().put("name", "").put("command", "x"))
            .put(org.json.JSONObject().put("name", "nothing to do"))
            .put(org.json.JSONObject().put("name", "n").put("command", 3))
            .put("not an object")
        assertEquals(emptyList<LaunchTemplate>(), LaunchTemplate.decode(bad))
        assertNull(LaunchTemplate.fromJson(org.json.JSONObject().put("name", "x".repeat(LaunchTemplate.MAX_NAME + 1)).put("command", "y")))
    }
}

/**
 * Running a launch template: a new tab, labelled with its name, that starts in its directory and
 * runs its command, and is a shell once the command ends. On ScriptedRex every run (what the phone
 * asks Rex for) and on a real Rex where one listens (what Rex makes of it).
 */
abstract class LaunchTemplateTest {
    abstract fun rex(): RexUnderTest

    protected open fun checkTab(rex: RexUnderTest, desk: Desk, sid: String, block: String, dir: File) {}

    @Test
    fun aTemplateOpensATabInItsDirectoryRunningItsCommand() = runBlocking {
        val rex = rex()
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = File(System.getProperty("java.io.tmpdir"), "rexpocket template ${System.nanoTime()}").apply { mkdirs() }
        try {
            desk.connect()
            val (sid, _) = desk.newSession(tabs = 1)
            phone.start(scope, preferredSessionId = sid)
            withTimeout(15_000) { phone.awaitReady() }
            phone.openSession(sid)
            val before = phone.state.value.views.getValue(sid).windows.map { it.id }
            val template = LaunchTemplate("t", "agent here", dir.absolutePath, "echo started-in-\$(pwd)")
            phone.newWindow(template)
            val tab = withTimeoutOrNull(10_000) {
                while (true) {
                    phone.state.value.views[sid]?.windows?.firstOrNull { it.id !in before }?.let { return@withTimeoutOrNull it }
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") null
            } ?: throw AssertionError("the template's tab never came")
            assertEquals("agent here", tab.label)
            checkTab(rex, desk, sid, tab.blocks.first().id, dir)
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
            dir.delete()
        }
    }
}

class ScriptedLaunchTemplateTest : LaunchTemplateTest() {
    private val scripted = ScriptedRex()

    override fun rex(): RexUnderTest = FakeRexUnderTest(scripted)

    override fun checkTab(rex: RexUnderTest, desk: Desk, sid: String, block: String, dir: File) {
        val asked = scripted.layouts.last()
        assertEquals(LaunchTemplate.SHELL_FLAVOR, asked.getString("flavor"))
        val command = asked.getJSONObject("options").getJSONArray("command")
        assertEquals("sh", command.getString(0))
        assertTrue(command.getString(2), command.getString(2).startsWith("cd '${dir.absolutePath}' && echo started-in-"))
    }
}

class RealRexLaunchTemplateTest : LaunchTemplateTest() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        return rex
    }

    override fun checkTab(rex: RexUnderTest, desk: Desk, sid: String, block: String, dir: File) = runBlocking {
        // The command ran in the directory, then the shell is there for the next line.
        val started = "started-in-${dir.absolutePath}"
        val text = withTimeoutOrNull(10_000) {
            while (true) {
                val t = desk.text(sid, block)
                if (t.contains(started)) return@withTimeoutOrNull t
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: throw AssertionError("the command never ran in ${dir.absolutePath}: ${desk.text(sid, block)}")
        assertTrue(text, text.contains(started))
        desk.type(sid, block, "echo still-a-shell-$((40+2))\r")
        withTimeoutOrNull(10_000) { while (!desk.text(sid, block).contains("still-a-shell-42")) delay(100) }
            ?: throw AssertionError("no shell after the command: ${desk.text(sid, block)}")
        Unit
    }
}
