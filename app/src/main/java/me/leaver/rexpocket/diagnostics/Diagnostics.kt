package me.leaver.rexpocket.diagnostics

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import me.leaver.rexpocket.BuildConfig
import me.leaver.rexpocket.core.DiagnosticReport
import me.leaver.rexpocket.core.DiagnosticsSession
import me.leaver.rexpocket.core.EventJournal
import me.leaver.rexpocket.core.EventLog
import me.leaver.rexpocket.core.ReportSection
import me.leaver.rexpocket.core.ReportedFact
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The record a bug report needs, and the file it travels in.
 *
 * A bug is reported after it happened, so the record cannot start at the sheet: the journal runs
 * all along, keeping the last few hours on the phone, and the report takes its end. When a problem
 * only shows up under the person's own hands, they start a recording instead - a file of its own,
 * written as it happens and across restarts - reproduce, and stop it. The report carries both
 * stretches. The addresses and the names their hosts go by are replaced on the way out (see
 * [DiagnosticReport]).
 */
object Diagnostics {
    private val moment = EventLog(limit = 500)

    /** Written from every thread that has something to say, and read from the sheet. */
    @Volatile private var session: DiagnosticsSession? = null

    /** The last few hours, on the phone, whether or not anyone asked for a report. */
    @Volatile private var journal: EventJournal? = null

    /** A recording in progress: how long it has run and how much it has written. */
    data class Capture(val startedAt: Long, val lines: Int, val bytes: Long)

    /** Adds a line for the report: to the journal, and to the recording when one is running. */
    fun event(tag: String, message: String) {
        val line = moment.add(tag, message)
        journal?.line(line)
        val current = session
        if (current != null && !current.line(line)) session = null
        if (BuildConfig.DEBUG) Log.i(tag, message)
    }

    /**
     * Called once per process, before anything has anything to say: opens the journal, picks up a
     * recording the last process left running, and watches for a crash.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        if (journal == null) journal = EventJournal(File(app.filesDir, "diagnostics/recent.log"))
        resume(app)
        watchCrashes(app)
    }

    /** The recording running now, or null. */
    fun capture(context: Context): Capture? {
        val current = session ?: return null
        if (!current.running) {
            // It ended by itself - full, or out of time - and says so in the file.
            session = null
            recording(context, false)
            return null
        }
        return Capture(System.currentTimeMillis() - current.elapsedMillis(), current.lines, file(context).length())
    }

    /** Starts a recording: what happens from here is written to the file line by line. */
    fun start(context: Context) {
        if (session != null) return
        val session = DiagnosticsSession(file(context))
        this.session = session
        recording(context, true)
        event(TAG, "recording started")
    }

    /** Stops the recording, saying why as the last line of the file. */
    fun stop(context: Context, reason: String) {
        val current = session ?: return
        session = null
        current.end(reason)
        recording(context, false)
    }

    /**
     * Picks a recording back up after the app was restarted: a bug that kills the process is
     * exactly the one worth recording.
     */
    fun resume(context: Context) {
        if (session != null) return
        if (!recording(context)) return
        val file = file(context)
        // A session that ran out of room or time said so as its last line: there is nothing to
        // carry on, and the next one starts from an empty file.
        if (lastLine(file)?.startsWith(ENDED) == true) {
            recording(context, false)
            return
        }
        val session = DiagnosticsSession(file, append = true)
        if (!session.running) {
            recording(context, false)
            return
        }
        this.session = session
        event(TAG, "recording resumed after a restart")
    }

    /** A crash during a recording goes into it: the file ends with what took the app down. */
    fun watchCrashes(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashRecorder) return
        Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(app, previous))
    }

    /**
     * The report: what the app and the phone are, [facts] about what it was doing, the last few
     * hours - which is where a problem noticed before this sheet was opened is - and the last
     * recording, when someone made one.
     */
    fun report(context: Context, facts: List<ReportedFact>, secrets: List<String>, limit: Int = Int.MAX_VALUE): String {
        val recent = journal?.tail(REPORT_LINES) ?: moment.snapshot()
        val sections = listOfNotNull(
            ReportSection("the last few hours", recent),
            lastRecording(context)?.let { ReportSection("the recording", it) },
        )
        return DiagnosticReport(
            title = "RexPocket ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            facts = environment(context) + facts,
            sections = sections,
            secrets = secrets,
        ).text(limit)
    }

    /**
     * Writes [text] to a file in the app's cache and offers it to another app - a mail client, a
     * messenger, anything that can carry an attachment. False when no file or no app was available.
     */
    fun share(context: Context, text: String): Boolean {
        val file = runCatching { write(context, text) }.getOrNull() ?: return false
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return false
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching {
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun lastLine(file: File): String? = runCatching { file.useLines { it.lastOrNull() } }.getOrNull()

    /** The lines the last recording kept, or null when nothing has been recorded. */
    private fun lastRecording(context: Context): List<String>? =
        file(context).takeIf { it.isFile }?.readLines()?.takeIf { it.isNotEmpty() }

    private fun file(context: Context) = File(context.filesDir, "diagnostics/session.txt")

    private fun write(context: Context, text: String): File {
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        // A report is the same short log every time, so only the last few are worth keeping.
        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_REPORTS - 1)?.forEach { it.delete() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        return File(directory, "rexpocket-${BuildConfig.VERSION_NAME}-$stamp.txt").apply { writeText(text) }
    }

    /** Whether a recording is on, kept in settings so a restart does not lose it. */
    private fun recording(context: Context, on: Boolean? = null): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (on == null) return prefs.getBoolean(KEY_RECORDING, false)
        prefs.edit().putBoolean(KEY_RECORDING, on).apply()
        return on
    }

    /** The phone the report comes from: what the app runs on, and how it is set up to draw. */
    private fun environment(context: Context): List<ReportedFact> {
        val config = context.resources.configuration
        val screen = context.resources.displayMetrics
        val night = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return listOf(
            ReportedFact("android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"),
            ReportedFact("device", "${Build.MANUFACTURER} ${Build.MODEL}"),
            ReportedFact("abi", Build.SUPPORTED_ABIS.joinToString(", ")),
            ReportedFact("locale", Locale.getDefault().toLanguageTag()),
            ReportedFact("screen", "${screen.widthPixels}x${screen.heightPixels} @${screen.density}"),
            ReportedFact("theme", if (night) "dark" else "light"),
            ReportedFact("font scale", "${(config.fontScale * 100).toInt()}%"),
            ReportedFact(
                "notifications",
                if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "allowed" else "blocked",
            ),
        )
    }

    private const val KEEP_REPORTS = 3

    /** How much of the last few hours a report carries: enough for a long evening, not a whole day. */
    private const val REPORT_LINES = 4_000
    private const val PREFS = "diagnostics"
    private const val KEY_RECORDING = "recording"
    private const val TAG = "RexPocketReport"

    /** The crash handler: it writes the failure into a running recording, then lets the app die. */
    private class CrashRecorder(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            val current = session
            if (current != null) {
                current.line("crash on ${thread.name}: ${error.javaClass.name}: ${error.message}")
                error.stackTrace.take(CRASH_FRAMES).forEach { current.line("    at $it") }
                current.end("the app crashed")
                session = null
                recording(context, false)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private const val CRASH_FRAMES = 40
    private const val ENDED = "— session ended:"
}
