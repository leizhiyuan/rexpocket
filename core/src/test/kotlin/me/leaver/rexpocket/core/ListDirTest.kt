package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Insert path's folder listing (`list_dir`), on ScriptedRex every run and on a real Rex where one
 * listens. Both list the machine the test runs on (a real Rex in CI is on loopback), so the folder
 * is made here with the JVM's own files.
 */
abstract class ListDirTest {
    abstract fun rex(): RexUnderTest

    private fun withPane(body: suspend (PocketSession, File) -> Unit) = runBlocking {
        val rex = rex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val phone = PocketSession(rex.endpoint)
        val dir = Files.createTempDirectory("rexpocket-ls").toFile().canonicalFile
        var sid: String? = null
        try {
            File(dir, "a dir").mkdir()
            File(dir, "Beta").mkdir()
            File(dir, ".hidden").mkdir()
            File(dir, "notes.txt").writeText("x")
            Files.createSymbolicLink(File(dir, "link").toPath(), File(dir, "Beta").toPath())
            Files.createSymbolicLink(File(dir, "dangling").toPath(), File(dir, "nowhere").toPath())
            phone.start(scope)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setSize(80, 24, 8, 16)
            phone.createShell("rexpocket-listdir-" + System.currentTimeMillis())
            sid = phone.state.value.activeSessionId
            body(phone, dir)
        } finally {
            if (sid != null) runCatching { phone.destroySession() }
            phone.stop()
            scope.cancel()
            rex.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFolderListsItsFoldersThenFilesWithLinksMarked() = withPane { phone, dir ->
        val listing = phone.listDir(dir.path)!!
        assertEquals(DirStatus.Ok, listing.status)
        assertEquals(dir.path, listing.path)
        assertFalse(listing.truncated)
        assertTrue("a home is reported: ${listing.home}", listing.home.startsWith("/"))
        // A link that leads nowhere is left out; folders first, dot entries after the others.
        assertEquals(listOf("a dir", "Beta", "link", ".hidden", "notes.txt"), listing.entries.map { it.name })
        assertEquals(listOf("notes.txt"), listing.entries.filter { it.file }.map { it.name })
        assertEquals(listOf("link"), listing.entries.filter { it.symlink }.map { it.name })
        assertEquals(listOf("a dir", "Beta", "link", "notes.txt"), listing.shown(hidden = false).map { it.name })
        assertEquals(dir.path + "/a dir", listing.child(listing.entries.first()))
        assertEquals(dir.parent, listing.parent)
    }

    @Test
    fun foldersOnlyWhenFilesAreNotAskedFor() = withPane { phone, dir ->
        val listing = phone.listDir(dir.path, files = false)!!
        assertEquals(listOf("a dir", "Beta", "link", ".hidden"), listing.entries.map { it.name })
    }

    @Test
    fun aMissingFolderAndAFileSaySo() = withPane { phone, dir ->
        assertEquals(DirStatus.Missing, phone.listDir(File(dir, "nope").path)!!.status)
        val file = phone.listDir(File(dir, "notes.txt").path)!!
        assertEquals(DirStatus.NotDir, file.status)
        assertTrue(file.entries.isEmpty())
    }

    @Test
    fun emptyIsTheProgramsWorkingDirectoryAndALimitTruncates() = withPane { phone, dir ->
        val here = phone.listDir("")!!
        assertEquals(DirStatus.Ok, here.status)
        assertTrue("an absolute path: ${here.path}", here.path.startsWith("/"))
        val cut = phone.listDir(dir.path, limit = 2)!!
        assertEquals(2, cut.entries.size)
        assertTrue(cut.truncated)
    }
}

class ScriptedListDirTest : ListDirTest() {
    override fun rex(): RexUnderTest = FakeRexUnderTest()
}

class RealRexListDirTest : ListDirTest() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        return rex
    }
}

/** The listing's reading and the shell's quoting, on their own. */
class DirListingTest {
    @Test
    fun rexsAnswerIsReadSortedAndNullEntriesAreNone() {
        val json = JSONObject(
            """{"path":"/w","home":"/root","status":"ok","entries":[{"name":"b"},{"name":"f.txt","file":true},{"name":".hid"},{"name":"lnk","symlink":true},{"name":"A dir"}],"truncated":false}""",
        )
        val l = DirListing.parse(json)
        assertEquals(listOf("A dir", "b", "lnk", ".hid", "f.txt"), l.entries.map { it.name })
        assertEquals("/w/A dir", l.child(l.entries[0]))
        assertEquals("/", l.parent)
        val missing = DirListing.parse(JSONObject("""{"path":"/x","home":"","status":"missing","entries":null,"truncated":false}"""))
        assertEquals(DirStatus.Missing, missing.status)
        assertTrue(missing.entries.isEmpty())
        assertEquals(DirStatus.Unknown, DirListing.parse(JSONObject("""{"path":"/x","status":"later"}""")).status)
    }

    @Test
    fun parents() {
        assertNull(DirListing.parentOf("/"))
        assertEquals("/", DirListing.parentOf("/usr"))
        assertEquals("/usr", DirListing.parentOf("/usr/lib/"))
    }

    @Test
    fun pathsAreQuotedOnlyWhenTheyNeedIt() {
        assertEquals("/usr/lib/x-1.2_a+b", shellPath("/usr/lib/x-1.2_a+b"))
        assertEquals("'/tmp/my notes'", shellPath("/tmp/my notes"))
        assertEquals("'/tmp/it'\\''s'", shellPath("/tmp/it's"))
        assertEquals("'/tmp/\$HOME*'", shellPath("/tmp/\$HOME*"))
        assertEquals("~", shellPath("/home/me", "/home/me"))
        assertEquals("~/src/app", shellPath("/home/me/src/app", "/home/me/"))
        assertEquals("~/'my notes'", shellPath("/home/me/my notes", "/home/me"))
        // Only a whole folder name is the home.
        assertEquals("/home/meg/x", shellPath("/home/meg/x", "/home/me"))
        assertEquals("/home/me/x", shellPath("/home/me/x", ""))
    }
}
