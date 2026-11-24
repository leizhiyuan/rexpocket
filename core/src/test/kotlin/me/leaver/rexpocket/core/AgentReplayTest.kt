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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64

/**
 * Coding agents' status reports replayed through Rex, step by step, as each agent writes them in a
 * real run (the fixtures in `agent-replays/`, from the agents' source or a capture made with
 * tools/agent-status-capture): working, blocked, working, done and idle across turns, with child
 * records where an agent uses them. After each step the phone's records must be Rex's, and the
 * home's lists what Rex's own records imply - Running when any record works, Needs you when one is
 * blocked or a done or failed one was not seen - and, at an `expect` line, what the fixture says.
 *
 * Fixture lines: `report <pairs>` is one OSC 7501 report (`| msg=` and `| title=` plain text, the
 * harness encodes them); `drop <id>...` is Rex dropping those records itself (`(root)` the root),
 * as when the program exits or the shell prompts; `see` is the person looking at the pane; `expect
 * running|needs-you|both|none` is the home's lists for the tab.
 *
 * [ScriptedAgentReplayTest] runs them on ScriptedRex (rex-integration); [RealRexAgentReplayTest]
 * on a real Rex (rex-real), where a loop in the pane's shell writes each report as an agent does.
 */
abstract class AgentReplayTest {
    abstract fun rex(): RexUnderTest

    /** Makes the program in the pane write the OSC 7501 report [pairs] (msg and title in base64). */
    abstract suspend fun report(world: World, pairs: String)

    /** Rex drops exactly the records [ids] ("" the root) on its own. */
    abstract suspend fun drop(world: World, ids: List<String>)

    /** Gets the pane ready to write reports, as an agent's process starting in it. */
    open suspend fun begin(world: World) {}

    class World(val rex: RexUnderTest, val desk: Desk, val phone: PocketSession, val sid: String, val tab: String, val block: String)

    /** One line of a fixture. */
    sealed interface Step {
        data class Report(val pairs: String) : Step
        data class Drop(val ids: List<String>) : Step
        data object See : Step
        data class Expect(val running: Boolean, val needsYou: Boolean) : Step
    }

    companion object {
        fun load(name: String): List<Step> {
            val text = AgentReplayTest::class.java.classLoader.getResource("agent-replays/$name.replay")!!.readText()
            return text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
                when {
                    line == "see" -> Step.See
                    line.startsWith("drop ") -> Step.Drop(line.removePrefix("drop ").split(' ').filter { it.isNotEmpty() }.map { if (it == "(root)") "" else it })
                    line.startsWith("expect ") -> when (val what = line.removePrefix("expect ").trim()) {
                        "none" -> Step.Expect(running = false, needsYou = false)
                        "running" -> Step.Expect(running = true, needsYou = false)
                        "needs-you" -> Step.Expect(running = false, needsYou = true)
                        "both" -> Step.Expect(running = true, needsYou = true)
                        else -> error("expect $what")
                    }
                    line.startsWith("report ") -> {
                        val parts = line.removePrefix("report ").split("|").map { it.trim() }
                        val text = parts.drop(1).joinToString("") { part ->
                            val key = part.substringBefore('=')
                            require(key == "msg" || key == "title") { part }
                            ":$key=" + Base64.getEncoder().encodeToString(part.substringAfter('=').toByteArray())
                        }
                        Step.Report(parts[0] + text)
                    }
                    else -> error("unknown step: $line")
                }
            }
        }

        fun records(rexRecords: JSONArray) = parseProgramRecords(JSONObject().put("records", rexRecords))

        fun shape(records: List<ProgramRecord>) = records.map { listOf(it.id, it.state, it.kind.orEmpty(), it.msg.orEmpty()) }.sortedBy { it[0] }
    }

    private suspend fun play(world: World, name: String) {
        val desk = world.desk
        val phone = world.phone
        val sid = world.sid
        val block = world.block
        // The person's seen marks, as the home keeps them.
        var mark: String? = null
        val seen: SeenCheck = { b, record -> b == block && markCovers(mark, record) }

        suspend fun check(where: String, fixture: Step.Expect?) {
            val st = phone.state.value
            val rexRecords = records(desk.programStatus(sid, block))
            // What Rex's own records imply, record by record.
            val implied = Step.Expect(
                running = rexRecords.any { it.state == "working" },
                needsYou = rexRecords.any { it.state == "blocked" || ((it.state == "done" || it.state == "error") && !markCovers(mark, it)) },
            )
            val got = Step.Expect(
                running = runningItems(st, seen).any { it.window.id == world.tab },
                needsYou = needsYouSections(st, seen).any { s -> s.items.any { it.window.id == world.tab } },
            )
            assertEquals("$where: what Rex's records imply (${shape(rexRecords)}; phone ${st.records[block]}; mark $mark)", implied, got)
            if (fixture != null) assertEquals("$where: what the fixture says", fixture, got)
        }

        for ((i, step) in load(name).withIndex()) {
            val where = "$name step ${i + 1} ($step)"
            when (step) {
                is Step.Report, is Step.Drop -> {
                    val before = desk.programStatus(sid, block).toString()
                    if (step is Step.Report) report(world, step.pairs) else drop(world, (step as Step.Drop).ids)
                    // Rex took it (a real shell writes it a moment later): every report changes
                    // Rex's records, if only their `updated_at`.
                    withTimeoutOrNull(10_000) { while (desk.programStatus(sid, block).toString() == before) delay(50) }
                        ?: throw AssertionError("$where: Rex never took it")
                    // The phone has what Rex has: every record, as Rex lists it.
                    withTimeoutOrNull(10_000) {
                        while (shape(records(desk.programStatus(sid, block))) != shape(phone.state.value.records[block].orEmpty())) delay(50)
                    } ?: throw AssertionError("$where: the phone never had Rex's records: rex=${desk.programStatus(sid, block)} phone=${phone.state.value.records[block]}")
                    check(where, null)
                }
                Step.See -> mark = mergeMarks(mark, seenMark(phone.state.value.records[block].orEmpty()))
                is Step.Expect -> check(where, step)
            }
        }
    }

    private fun replay(name: String) = runBlocking {
        val under = rex()
        val desk = Desk(under.endpoint)
        val phone = PocketSession(under.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var made: String? = null
        try {
            desk.connect()
            val (sid, tabs) = desk.newSession(tabs = 1)
            made = sid
            val (tab, block) = tabs[0]
            phone.start(scope, preferredSessionId = sid)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setActive(true)
            val world = World(under, desk, phone, sid, tab, block)
            begin(world)
            play(world, name)
        } finally {
            phone.stop()
            scope.cancel()
            // A real Rex is shared by the tests after this one: what this made goes.
            made?.let { runCatching { desk.destroy(it) } }
            desk.close()
            under.close()
        }
    }

    @Test
    fun omp() = replay("omp")

    @Test
    fun codex() = replay("codex")

    @Test
    fun claudeCode() = replay("claude-code")

    @Test
    fun anAgentWithChildRecords() = replay("subagents")

    /** Every other fixture in the directory: captures added with tools/agent-status-capture. */
    @Test
    fun everyCapturedFixture() {
        val dir = java.io.File(AgentReplayTest::class.java.classLoader.getResource("agent-replays")!!.toURI())
        val named = setOf("omp", "codex", "claude-code", "subagents")
        for (file in dir.listFiles().orEmpty().filter { it.name.endsWith(".replay") }.sortedBy { it.name }) {
            val name = file.name.removeSuffix(".replay")
            if (name !in named) replay(name)
        }
    }
}

class ScriptedAgentReplayTest : AgentReplayTest() {
    override fun rex(): RexUnderTest = FakeRexUnderTest()

    private fun rexOf(world: World) = (world.rex as FakeRexUnderTest).rex

    /** ScriptedRex is told the report as the protocol reads it: pairs, the text in base64. */
    override suspend fun report(world: World, pairs: String) {
        val kv = pairs.split(':').associate { it.substringBefore('=') to it.substringAfter('=') }
        fun text(key: String) = kv[key]?.let { String(Base64.getDecoder().decode(it)) }
        val state = kv.getValue("state").takeIf { it != "clear" }
        rexOf(world).report(world.block, state, kind = kv["kind"], msg = text("msg"), app = kv["app"], title = text("title"), id = kv["id"] ?: "")
    }

    override suspend fun drop(world: World, ids: List<String>) = rexOf(world).dropRecords(world.block, ids)
}

class RealRexAgentReplayTest : AgentReplayTest() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        return rex
    }

    /**
     * The pane's shell runs a loop that writes each line typed into it as one OSC 7501 report, as
     * an agent's process writes them: no shell prompt comes between reports, which would make Rex
     * drop working and blocked records (docs:program-status, Lifetime).
     */
    override suspend fun begin(world: World) {
        delay(1_500)
        world.desk.type(world.sid, world.block, "while IFS= read -r l; do printf '\\033]7501;%s\\033\\\\' \"\$l\"; done\n")
        delay(500)
    }

    override suspend fun report(world: World, pairs: String) {
        world.desk.type(world.sid, world.block, "$pairs\n")
    }

    /**
     * A program cannot make Rex drop one record the way an exit or a prompt does; the records are
     * cleared and those that stay are reported again, which leaves Rex with the same set.
     */
    override suspend fun drop(world: World, ids: List<String>) {
        val kept = records(world.desk.programStatus(world.sid, world.block)).filter { it.id !in ids }
        world.desk.type(world.sid, world.block, "state=clear\n")
        delay(300)
        for (r in kept) {
            val pairs = buildList {
                if (r.id.isNotEmpty()) add("id=${r.id}")
                add("state=${r.state}")
                r.kind?.let { add("kind=$it") }
                r.reportedApp?.let { add("app=$it") }
                r.title?.let { add("title=" + Base64.getEncoder().encodeToString(it.toByteArray())) }
                r.msg?.let { add("msg=" + Base64.getEncoder().encodeToString(it.toByteArray())) }
            }.joinToString(":")
            world.desk.type(world.sid, world.block, "$pairs\n")
        }
    }
}
