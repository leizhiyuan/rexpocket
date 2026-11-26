package me.leaver.rexpocket

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.leaver.rexpocket.core.AppRelease
import me.leaver.rexpocket.core.CheckFailure
import me.leaver.rexpocket.core.CheckReport
import me.leaver.rexpocket.core.CheckStep
import me.leaver.rexpocket.core.CheckTarget
import me.leaver.rexpocket.core.ConnectionCheck
import me.leaver.rexpocket.core.SshAuthException
import me.leaver.rexpocket.core.SshCredentials
import me.leaver.rexpocket.core.SshKeys
import me.leaver.rexpocket.core.SshSettings
import me.leaver.rexpocket.core.SshTunnel
import me.leaver.rexpocket.core.StepResult
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.terminal.TerminalSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * What R8 could break that no screen test reaches: the code found by name at run time. Written for
 * the `minified` build (device-test.yml's minified job, see docs/design/r8.md), and passes on the
 * debug build too. The SSH checks need a real OpenSSH server, which that job starts on the runner
 * and names in the instrumentation arguments; without them they are skipped.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedSmokeTest {
    private val args = InstrumentationRegistry.getArguments()

    /** libghostty's JNI_OnLoad finds GhosttyVt and its decodePng callback by name; a Kitty PNG calls it. */
    @Test fun aKittyPngImageIsDecodedThroughTheJniCallback() {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.RED)
        bitmap.setPixel(1, 0, Color.BLUE)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val session = requireNotNull(TerminalSession.create(10, 5)) { "no terminal" }
        try {
            session.resize(10, 5, 8, 16)
            val escape = "\u001b_Ga=T,f=100,q=2,i=9;" + Base64.getEncoder().encodeToString(png) + "\u001b\\"
            session.feedOutput(("hi\r\n" + escape).toByteArray())
            val pixels = requireNotNull(session.imagePixels(9)) { "the PNG was not decoded" }
            assertEquals(2, pixels.width)
            assertEquals(1, pixels.height)
            assertArrayEquals(intArrayOf(Color.RED, Color.BLUE), pixels.argb)
        } finally {
            session.close()
        }
    }

    /** A key made on the phone (eddsa) is read back by JSch, through the classes it loads by name. */
    @Test fun aGeneratedKeyIsReadBackByJsch() {
        // An SshTunnel registers the app's Ed25519 classes with JSch, as any connection does first.
        SshTunnel(SshSettings(host = "127.0.0.1", user = "nobody")).close()
        val key = SshKeys.generate("rexpocket:smoke")
        assertEquals(key.publicKey, SshKeys.publicKeyOf(key.privateKey))
    }

    /** The diagnostics log and the problem report name exceptions and tailnet stages by class. */
    @Test fun theNamesDiagnosticsShowAreTheRealOnes() {
        assertEquals("SshAuthException", SshAuthException("x").javaClass.simpleName)
        assertEquals("Connecting", TailnetStage.Connecting.javaClass.simpleName)
    }

    /** The update check: OkHttp, and org.json reading the release feed. */
    @Test fun anUpdateFeedIsFetchedAndRead() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """[{"tag_name":"v9.0.0","prerelease":false,"draft":false,"assets":[{"name":"RexPocket-v9.0.0.apk",""" +
                        """"browser_download_url":"https://example.test/a.apk","size":42,"digest":"sha256:abc"}]}]""",
                ),
            )
            server.start()
            val body = OkHttpClient().newCall(Request.Builder().url(server.url("/releases")).build()).execute().use { it.body!!.string() }
            val newest = AppRelease.parseFeed(body).maxByOrNull { it.version }
            assertEquals("v9.0.0", newest?.tag)
            assertEquals(42L, newest?.apk?.size)
        }
    }

    @Test fun sshWithAnEd25519HostKey() = sshHost("sshPortEd25519")

    @Test fun sshWithAnRsaHostKey() = sshHost("sshPortRsa")

    /**
     * A new host key is shown for trust, then the same check with it trusted signs in with a password,
     * an Ed25519 key and an RSA key: JSch's ciphers, key exchanges and signatures are all made by name.
     * The runner has no Rex, so the check ends there.
     */
    private fun sshHost(portArg: String) {
        val host = args.getString("sshHost")
        val port = args.getString(portArg)?.toIntOrNull()
        assumeTrue("no SSH server given (instrumentation argument $portArg)", host != null && port != null)
        val user = args.getString("sshUser").orEmpty()
        fun key(name: String) = String(Base64.getDecoder().decode(requireNotNull(args.getString(name)) { "no $name" }))
        val target = CheckTarget(host!!, port!!, plaintext = true, sshUser = user, credentials = SshCredentials(password = args.getString("sshPassword")))

        val first = check(target)
        val seen = first.of(CheckStep.HostKey)
        assertEquals(first.toString(), CheckFailure.HostKeyUnknown, (seen as? StepResult.Failed)?.failure)
        val fingerprint = requireNotNull(first.seenHostKey) { "no host key seen: $first" }

        val trusted = target.copy(hostKey = fingerprint)
        for ((how, credentials) in listOf(
            "password" to trusted.credentials,
            "Ed25519 key" to SshCredentials(privateKey = key("sshKeyEd25519")),
            "RSA key" to SshCredentials(privateKey = key("sshKeyRsa")),
        )) {
            val report = check(trusted.copy(credentials = credentials))
            assertEquals("$how: $report", StepResult.Passed(fingerprint), report.of(CheckStep.HostKey))
            assertEquals("$how: $report", StepResult.Passed::class, report.of(CheckStep.SignIn)!!::class)
        }
    }

    private fun check(target: CheckTarget): CheckReport = runBlocking { withTimeout(120_000) { ConnectionCheck(target).run() } }
}
