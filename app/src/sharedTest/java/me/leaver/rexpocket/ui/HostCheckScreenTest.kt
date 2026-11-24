package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.CheckFailure
import me.leaver.rexpocket.core.CheckReport
import me.leaver.rexpocket.core.CheckRoute
import me.leaver.rexpocket.core.CheckStep
import me.leaver.rexpocket.core.ScriptedRex
import me.leaver.rexpocket.core.StepResult
import me.leaver.rexpocket.data.SavedHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

/**
 * The host dialog's Test connection: each step said as it goes, why one failed and what to do, a
 * host key to trust for the test, and nothing saved by any of it. See docs/design/connection-test.md.
 */
@RunWith(AndroidJUnit4::class)
class HostCheckScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rex = ScriptedRex()
    private val tester = HostTester(scope) { CheckRoute() }

    @After
    fun stop() {
        scope.cancel()
        rex.close()
    }

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun seen(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun said(text: String): Boolean = compose.onAllNodes(
        SemanticsMatcher("says $text") { n -> n.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { it.contains(text) } },
    ).fetchSemanticsNodes().isNotEmpty()

    private fun panel(host: SavedHost, trusted: MutableList<String> = mutableListOf()) {
        compose.setContent {
            RexTheme {
                val report by tester.report.collectAsState()
                HostCheckPanel(
                    report = report,
                    running = report?.finished == false,
                    enabled = true,
                    pinned = null,
                    trusted = trusted.lastOrNull(),
                    onRun = { tester.start(host.checkTarget()) },
                    onCancel = tester::cancel,
                    onTrust = { trusted += it },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aRexPortPassesEveryStepAndSaysItsApi() {
        panel(SavedHost(id = "x", label = "desk", host = "127.0.0.1", port = rex.endpoint.port, plaintext = true))
        assertTrue(seen(string(R.string.check_saves_nothing)))
        compose.onNodeWithText(string(R.string.check_run)).performClick()
        compose.awaitThat { tester.report.value?.finished == true }
        assertTrue(tester.report.value.toString(), tester.report.value!!.passed)
        compose.awaitThat { seen(string(R.string.check_all_passed)) }
        assertTrue(said(string(R.string.check_api, 19)))
        // One TalkBack stop per step, with its name and its state.
        assertTrue(said(string(R.string.check_step_rex) + ". " + string(R.string.check_state_passed)))
        assertTrue(seen(string(R.string.check_again)))
    }

    @Test
    fun aClosedPortSaysWhyAndWhatToDo() {
        val port = ServerSocket(0).use { it.localPort }
        panel(SavedHost(id = "x", label = "gone", host = "127.0.0.1", port = port, plaintext = true))
        compose.onNodeWithText(string(R.string.check_run)).performClick()
        compose.awaitThat { tester.report.value?.finished == true }
        // Each step is one stop for TalkBack: its words are its description.
        compose.awaitThat { said(string(R.string.check_fail_refused)) }
        assertTrue(said(string(R.string.check_fix_refused)))
        assertTrue(said(string(R.string.check_step_connect) + ". " + string(R.string.check_state_failed)))
        assertTrue(said(string(R.string.check_step_rex) + ". " + string(R.string.check_state_not_reached)))
    }

    @Test
    fun aHostKeyNotTrustedYetIsShownWithAButtonToTrustIt() {
        val fp = "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
        val trusted = mutableListOf<String>()
        val report = CheckReport(
            steps = listOf(
                CheckStep.Resolve to StepResult.Passed("10.0.0.2"),
                CheckStep.Connect to StepResult.Passed("box:22"),
                CheckStep.HostKey to StepResult.Failed(CheckFailure.HostKeyUnknown, fp),
                CheckStep.SignIn to StepResult.NotReached,
                CheckStep.Rex to StepResult.NotReached,
                CheckStep.Api to StepResult.NotReached,
            ),
            seenHostKey = fp,
            finished = true,
        )
        compose.setContent {
            RexTheme {
                HostCheckPanel(report, running = false, enabled = true, pinned = null, trusted = null, onRun = {}, onCancel = {}, onTrust = { trusted += it })
            }
        }
        compose.waitForIdle()
        assertTrue(seen(fp))
        assertTrue(said(string(R.string.check_fail_key_unknown)))
        compose.onNodeWithText(string(R.string.check_trust)).performClick()
        assertEquals(listOf(fp), trusted)
    }

    /** The dialog itself: the test runs on what is typed and Save is never called by it. Fields never idle on Robolectric. */
    @Test
    fun theDialogsTestSavesNothing() {
        assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)
        val host = SavedHost(id = "x", label = "desk", host = "127.0.0.1", port = rex.endpoint.port, plaintext = true)
        val saved = mutableListOf<SavedHost>()
        compose.setContent {
            RexTheme {
                HostDialog(initial = host, tailnetEnabled = false, onDismiss = {}, onDelete = null, onSave = { saved += it }, onJoinTailnet = {}, tester = tester)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.check_run)).performScrollTo().performClick()
        compose.awaitThat { tester.report.value?.finished == true }
        assertTrue(tester.report.value!!.passed)
        assertTrue(saved.isEmpty())
    }
}
