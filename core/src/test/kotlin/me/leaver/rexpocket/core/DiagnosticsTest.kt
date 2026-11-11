package me.leaver.rexpocket.core

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun report(
        events: List<String> = emptyList(),
        facts: List<ReportedFact> = emptyList(),
        secrets: List<String> = emptyList(),
    ) = DiagnosticReport(
        "RexPocket 0.0.15 (16)",
        facts,
        listOf(ReportSection("the last few hours", events)),
        secrets,
    )

    @Test fun theLogKeepsTheLastLinesInOrder() {
        val log = EventLog(limit = 3)
        for (i in 1..5) log.add("conn", "line $i")

        assertEquals(listOf("line 3", "line 4", "line 5"), log.snapshot().map { it.substringAfter("conn: ") })
    }

    @Test fun aReportSaysWhatItIsAndWhatHappened() {
        val text = report(
            facts = listOf(ReportedFact("android", "16 (SDK 36)"), ReportedFact("phase", "Live")),
            events = listOf("12:00:00.000 conn: connect devbox:7297"),
        ).text()

        assertTrue(text.startsWith("RexPocket 0.0.15 (16)\n"))
        assertTrue(text.contains("android: 16 (SDK 36)"))
        assertTrue(text.contains("phase: Live"))
        assertTrue(text.contains("the last few hours:\n  12:00:00.000 conn: connect devbox:7297\n"))
    }

    @Test fun aQuietSheetSaysSo() {
        assertTrue(report().text().contains("events:\n  (none)\n"))
    }

    @Test fun anAddressKeepsItsNetworkAndLosesTheRest() {
        val text = report(events = listOf("connect 192.168.1.23:7297 and 203.0.113.9:443")).text()

        assertTrue(text.contains("192.168.x.x:7297"))
        assertTrue(text.contains("203.0.x.x:443"))
        assertFalse(text.contains("192.168.1.23"))
        assertFalse(text.contains("203.0.113.9"))
    }

    @Test fun aMagicDnsNameLosesTheNodeAndKeepsTheTailnet() {
        val text = report(events = listOf("dialing my-mac.tail1234.ts.net")).text()

        assertTrue(text.contains("<hidden>.ts.net"))
        assertFalse(text.contains("my-mac"))
    }

    @Test fun theNamesThePersonGaveTheirHostsAreReplaced() {
        val text = report(
            facts = listOf(ReportedFact("host", "work-laptop at devbox.lan")),
            events = listOf("opened a tab on work-laptop"),
            secrets = listOf("work-laptop", "devbox.lan"),
        ).text()

        assertFalse(text.contains("work-laptop"))
        assertFalse(text.contains("devbox.lan"))
        assertTrue(text.contains("<hidden> at <hidden>"))
    }

    /** One host's address can be the beginning of another's, so the longer one goes first. */
    @Test fun anAddressInsideAnotherIsHiddenToo() {
        val text = report(
            events = listOf("tried 10.0.2.20 then devbox"),
            secrets = listOf("devbox", "10.0.2.2", "10.0.2.20"),
        ).text()

        assertFalse(text.contains("devbox"))
        assertTrue(text.contains("10.0.x.x"))
    }

    /** A recording is written as it happens, so a bug that ends the app still leaves it behind. */
    @Test fun aSessionWritesEachLineAsItHappens() {
        val file = temp.newFile("session.txt")
        val session = DiagnosticsSession(file)

        assertTrue(session.line("one"))
        assertEquals("one\n", file.readText())
        assertTrue(session.line("two"))

        session.end("stopped by the person")
        assertEquals("one\ntwo\n— session ended: stopped by the person\n", file.readText())
        assertFalse("nothing is written after it ends", session.line("three"))
        assertEquals(2, session.lines)
    }

    @Test fun aSessionEndsWhenItIsFull() {
        val file = temp.newFile("session.txt")
        val session = DiagnosticsSession(file, limitBytes = 15)

        assertTrue(session.line("12345678"))
        assertFalse(session.line("12345678"))
        assertTrue(file.readText().endsWith("— session ended: the session filled 0 MB\n"))
        assertFalse(session.running)
    }

    @Test fun aSessionEndsWhenItsTimeIsUp() {
        val file = temp.newFile("session.txt")
        var now = 1_000L
        val session = DiagnosticsSession(file, limitMillis = 60_000, clock = { now })

        now += 61_000
        assertFalse(session.line("late"))
        assertTrue(file.readText().contains("— session ended: the session ran its 1 minutes"))
    }

    /** Half an hour of a terminal app is far more than a person reads, so a paste keeps the end. */
    @Test fun aPasteKeepsTheEndOfALongSession() {
        val text = report(events = (1..500).map { "line $it" }).text(limit = 900)

        assertTrue(text.length < 1_200)
        assertTrue(text.contains("line 500"))
        assertFalse(text.contains("line 1\n"))
        assertTrue(text.contains("earlier lines left out"))
    }

    @Test fun aShortSessionIsKeptWhole() {
        val text = report(events = listOf("line 1", "line 2")).text(limit = 10_000)

        assertTrue(text.contains("line 1"))
        assertTrue(text.contains("line 2"))
        assertFalse(text.contains("left out"))
    }
}


class EventJournalTest {
    @get:Rule val temp = TemporaryFolder()

    private fun journal(limit: Long = 1_000) = EventJournal(File(temp.root, "recent.log"), limitBytes = limit)

    @Test fun aLineIsThereAtOnce() {
        val journal = journal()
        journal.line("first")

        assertEquals("first\n", File(temp.root, "recent.log").readText())
    }

    /** It cannot grow without a bound: the file is rotated to `.1`, and only that one is kept. */
    @Test fun theJournalRotatesAndKeepsOneBackup() {
        val journal = journal(limit = 24)
        for (i in 1..10) journal.line("line $i")

        val current = File(temp.root, "recent.log")
        val backup = File(temp.root, "recent.log.1")
        assertTrue(backup.isFile)
        assertFalse(File(temp.root, "recent.log.2").exists())
        assertTrue(current.length() <= 24)
        assertEquals("line 10\n", current.readLines().last() + "\n")
    }

    @Test fun theTailReachesBackIntoTheRotatedFile() {
        val journal = journal(limit = 24)
        for (i in 1..10) journal.line("line $i")

        val tail = journal.tail(5)
        // Only one backup is kept, so the oldest lines are gone and the rest are there.
        assertEquals(listOf("line 7", "line 8", "line 9", "line 10"), tail)
    }

    @Test fun theTailOfACurrentFileIsItsOwnEnd() {
        val journal = journal(limit = 1_000)
        for (i in 1..5) journal.line("line $i")

        assertEquals(listOf("line 3", "line 4", "line 5"), journal.tail(3))
        assertEquals(emptyList<String>(), journal(limit = 1_000).tail(0))
    }
}

class DiagnosticsSectionsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun eachRecordingIsANamedSection() {
        val text = DiagnosticReport(
            "RexPocket 0.0.15 (16)",
            listOf(ReportedFact("phase", "Live")),
            listOf(
                ReportSection("the last few hours", listOf("11:00 moment")),
                ReportSection("the recording", listOf("11:01 recorded")),
            ),
            emptyList(),
        ).text()

        assertTrue(text.contains("the last few hours:\n  11:00 moment\n"))
        assertTrue(text.contains("the recording:\n  11:01 recorded\n"))
        assertTrue(text.indexOf("the last few hours") < text.indexOf("the recording"))
    }

    @Test fun anEmptySectionIsLeftOut() {
        val text = DiagnosticReport(
            "RexPocket 0.0.15 (16)",
            emptyList(),
            listOf(ReportSection("the last few hours", emptyList()), ReportSection("the recording", listOf("x"))),
            emptyList(),
        ).text()

        assertFalse(text.contains("the last few hours"))
        assertTrue(text.contains("the recording:\n  x\n"))
    }
}
