package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The terminal's own features end to end, from the menu to what reaches Rex: Insert path over
 * ScriptedRex's `list_dir`, which lists the computer's own home on the JVM. JVM only, where that
 * home can be written to.
 */
@RunWith(AndroidJUnit4::class)
class TermEdgeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 1)
    private var model: RexViewModel? = null
    private var made: File? = null

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
        made?.deleteRecursively()
    }

    private fun onTerminal(): RexViewModel {
        val app = freshApp()
        desk.saveFor(app, lastSession = desk.ids[0])
        val m = launchedModel(compose, app, killedOnTerminal = true)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.view?.focusedBlock() != null }
        return m
    }

    /** Insert path showing [folder] as its heading, with [entry] listed in it. */
    private fun awaitFolder(folder: String, entry: String) = compose.awaitThat {
        compose.onAllNodes(hasContentDescription(folder)).fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag(insertPathEntryTag(entry))).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Insert path from the ⋮ menu: it starts at the program's folder (the home here, its heading
     * `~`), opens a folder with a quote and a space in its name, goes Up and back, and types a file
     * whose name has a space and a `$` as one shell word: under the home with `~/`, the rest
     * single-quoted, a quote in it as `'\''`. Nothing is run: it is a paste, no Return. (Names past
     * ASCII are quoted the same way, ListDirTest; this JVM may not write them to its disk.)
     */
    @Test
    fun insertPathTypesAnOddlyNamedFileAsOneShellWord() {
        assumeTrue(onRobolectric)
        val home = File(System.getProperty("user.home")).absoluteFile
        val root = File(home, "aaa-rexpocket-insert-${System.nanoTime()}").also { made = it }
        val folder = File(root, "it's here").apply { mkdirs() }
        File(folder, "my \$file.txt").writeText("x")
        val m = onTerminal()
        val block = m.pocket.value.view!!.focusedBlock()!!.id

        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.insert_path)).performScrollTo().performClick()
        compose.awaitThat { compose.onAllNodes(hasContentDescription("~")).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(androidx.compose.ui.test.hasTestTag(insertPathEntryTag(root.name))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(insertPathEntryTag(root.name)).performScrollTo().performClick()
        // The heading names a folder as soon as it is asked for; its rows come with Rex's answer.
        awaitFolder("~/${root.name}", "it's here")
        compose.onNodeWithTag(insertPathEntryTag("it's here")).performScrollTo().performClick()
        awaitFolder("~/${root.name}/it's here", "my \$file.txt")
        compose.onNodeWithTag(INSERT_PATH_UP_TAG).performClick()
        awaitFolder("~/${root.name}", "it's here")
        compose.onNodeWithTag(insertPathEntryTag("it's here")).performScrollTo().performClick()
        awaitFolder("~/${root.name}/it's here", "my \$file.txt")
        compose.onNodeWithTag(insertPathEntryTag("my \$file.txt")).performScrollTo().performClick()
        compose.awaitThat { desk.rex.inputs.isNotEmpty() }
        assertEquals(listOf("paste $block ~/'${root.name}/it'\\''s here/my \$file.txt'"), desk.rex.inputs.toList())
    }
}
