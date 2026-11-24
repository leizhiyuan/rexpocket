package me.leaver.rexpocket.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import kotlin.concurrent.thread

/**
 * The host dialog's Test connection, step by step, against a scripted Rex, things that are not Rex,
 * and a real SSH server (Apache MINA) whose exec channel reaches the scripted Rex as
 * `rex __internal dial-stdio` does. See docs/design/connection-test.md.
 */
class ConnectionCheckTest {
    private val closers = mutableListOf<AutoCloseable>()

    @After
    fun close() {
        closers.reversed().forEach { runCatching { it.close() } }
    }

    private fun <T : AutoCloseable> T.closed(): T = also { closers += it }

    private fun steps(r: CheckReport) = r.steps.map { it.first }

    private fun run(target: CheckTarget, timeouts: CheckTimeouts = CheckTimeouts(), route: suspend () -> CheckRoute = { CheckRoute() }) =
        runBlocking { withTimeout(60_000) { ConnectionCheck(target, route = route, timeouts = timeouts).run() } }

    @Test
    fun aRexPortPassesEveryStepAndSaysItsApiRevision() {
        val rex = ScriptedRex().closed()
        val seen = mutableListOf<CheckReport>()
        val report = runBlocking {
            ConnectionCheck(CheckTarget("127.0.0.1", rex.endpoint.port, plaintext = true)).run { seen += it }
        }
        assertEquals(listOf(CheckStep.Resolve, CheckStep.Connect, CheckStep.Rex, CheckStep.Api), steps(report))
        assertTrue(report.toString(), report.passed)
        assertEquals(19, report.apiRevision)
        assertEquals(StepResult.Passed("19"), report.of(CheckStep.Api))
        assertNull(report.seenHostKey)
        // Each step was shown running before it passed.
        assertTrue(seen.any { it.of(CheckStep.Connect) == StepResult.Running })
    }

    @Test
    fun aClosedPortIsRefusedAndTheRestIsNotTried() {
        val port = ServerSocket(0).use { it.localPort }
        val report = run(CheckTarget("127.0.0.1", port, plaintext = true))
        assertEquals(CheckFailure.Refused, (report.of(CheckStep.Connect) as StepResult.Failed).failure)
        assertEquals(StepResult.NotReached, report.of(CheckStep.Rex))
        assertTrue(report.finished)
    }

    @Test
    fun aNameThatDoesNotResolveStopsAtTheName() {
        val report = runBlocking {
            ConnectionCheck(CheckTarget("nowhere.invalid", 7297, plaintext = true), resolve = { throw UnknownHostException(it) }).run()
        }
        assertEquals(CheckFailure.UnknownHost, (report.of(CheckStep.Resolve) as StepResult.Failed).failure)
        assertEquals(StepResult.NotReached, report.of(CheckStep.Connect))
    }

    @Test
    fun aWebServerThatIsNotRexIsSaidToBeNotRex() {
        val web = MockWebServer().closed()
        web.enqueue(MockResponse().setResponseCode(404))
        web.start()
        val report = run(CheckTarget("127.0.0.1", web.port, plaintext = true))
        assertEquals(StepResult.Passed("127.0.0.1:${web.port}"), report.of(CheckStep.Connect))
        assertEquals(CheckFailure.NotRex, (report.of(CheckStep.Rex) as StepResult.Failed).failure)
    }

    @Test
    fun aRexThatRefusesThisPhoneIsSaidSo() {
        val web = MockWebServer().closed()
        web.enqueue(MockResponse().setResponseCode(401))
        web.start()
        val report = run(CheckTarget("127.0.0.1", web.port, plaintext = true))
        assertEquals(CheckFailure.RexRefused, (report.of(CheckStep.Rex) as StepResult.Failed).failure)
    }

    @Test
    fun aPortThatNeverAnswersTimesOutAtRex() {
        val silent = SilentServer().closed()
        val report = run(CheckTarget("127.0.0.1", silent.port, plaintext = true), CheckTimeouts(rexMs = 800))
        assertEquals(CheckFailure.RexNoAnswer, (report.of(CheckStep.Rex) as StepResult.Failed).failure)
    }

    @Test
    fun cancellingEndsTheCheckAtOnceWithTheRunningStepCanceled() = runBlocking {
        val silent = SilentServer().closed()
        val running = CompletableDeferred<Unit>()
        var last: CheckReport? = null
        val job = async {
            ConnectionCheck(CheckTarget("127.0.0.1", silent.port, plaintext = true)).run { r ->
                last = r
                if (r.of(CheckStep.Rex) == StepResult.Running) running.complete(Unit)
            }
        }
        withTimeout(10_000) { running.await() }
        val started = System.nanoTime()
        job.cancelAndJoin()
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 3_000_000_000L)
        val report = last!!
        assertTrue(report.finished)
        assertEquals(StepResult.Failed(CheckFailure.Canceled), report.of(CheckStep.Rex))
        assertEquals(StepResult.NotReached, report.of(CheckStep.Api))
    }

    @Test
    fun aTailnetHostUsesTheRouteItIsGivenAndSaysWhyThereIsNone() {
        val rex = ScriptedRex().closed()
        val target = CheckTarget("127.0.0.1", rex.endpoint.port, plaintext = true, viaTailnet = true)
        val ok = run(target)
        assertEquals(listOf(CheckStep.Tailnet, CheckStep.Connect, CheckStep.Rex, CheckStep.Api), steps(ok))
        assertTrue(ok.toString(), ok.passed)

        val none = run(target) { throw CheckRouteException(CheckFailure.TailnetNotJoined, null) }
        assertEquals(CheckFailure.TailnetNotJoined, (none.of(CheckStep.Tailnet) as StepResult.Failed).failure)
        assertEquals(StepResult.NotReached, none.of(CheckStep.Connect))
    }

    // SSH, on a real server.

    @Test
    fun anSshHostShowsItsKeyForTrustThenSignsInAndReachesRex() {
        val rex = ScriptedRex().closed()
        val ssh = TestSshd(rexPort = rex.endpoint.port).closed()
        val target = CheckTarget("127.0.0.1", ssh.port, plaintext = true, sshUser = "me", credentials = SshCredentials(password = "pw"))

        val first = run(target)
        assertEquals(
            listOf(CheckStep.Resolve, CheckStep.Connect, CheckStep.HostKey, CheckStep.SignIn, CheckStep.Rex, CheckStep.Api),
            steps(first),
        )
        assertEquals(StepResult.Failed(CheckFailure.HostKeyUnknown, ssh.fingerprint), first.of(CheckStep.HostKey))
        assertEquals(ssh.fingerprint, first.seenHostKey)
        assertEquals(StepResult.NotReached, first.of(CheckStep.SignIn))

        // Trusted for the test: the same check with that key passes every step.
        val trusted = run(target.copy(hostKey = first.seenHostKey))
        assertTrue(trusted.toString(), trusted.passed)
        assertEquals(StepResult.Passed(ssh.fingerprint), trusted.of(CheckStep.HostKey))
        assertEquals(19, trusted.apiRevision)
    }

    @Test
    fun anSshHostWhoseKeyIsNotThePinnedOneIsAMismatch() {
        val ssh = TestSshd(rexPort = 1).closed()
        val other = "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        val report = run(CheckTarget("127.0.0.1", ssh.port, true, "me", SshCredentials(password = "pw"), hostKey = other))
        assertEquals(StepResult.Failed(CheckFailure.HostKeyChanged, ssh.fingerprint), report.of(CheckStep.HostKey))
    }

    @Test
    fun aWrongPasswordPassesTheKeyAndFailsTheSignIn() {
        val ssh = TestSshd(rexPort = 1).closed()
        val report = run(CheckTarget("127.0.0.1", ssh.port, true, "me", SshCredentials(password = "nope"), hostKey = ssh.fingerprint))
        assertEquals(StepResult.Passed(ssh.fingerprint), report.of(CheckStep.HostKey))
        assertEquals(CheckFailure.SignInRefused, (report.of(CheckStep.SignIn) as StepResult.Failed).failure)
        assertEquals(StepResult.NotReached, report.of(CheckStep.Rex))
    }

    @Test
    fun aMachineWithoutRexIsSaidToHaveNone() {
        val ssh = TestSshd(rexPort = null).closed()
        val report = run(CheckTarget("127.0.0.1", ssh.port, true, "me", SshCredentials(password = "pw"), hostKey = ssh.fingerprint))
        assertEquals(StepResult.Passed(), report.of(CheckStep.SignIn))
        assertEquals(CheckFailure.RexMissing, (report.of(CheckStep.Rex) as StepResult.Failed).failure)
    }

    /** Accepts connections and never says a word. */
    private class SilentServer : AutoCloseable {
        private val server = ServerSocket(0)
        private val held = mutableListOf<Socket>()
        val port = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) runCatching { synchronized(held) { held += server.accept() } }
            }
        }

        override fun close() {
            server.close()
            synchronized(held) { held.forEach { runCatching { it.close() } } }
        }
    }

}
