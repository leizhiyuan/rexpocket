package me.leaver.rexpocket.core

import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the app did, kept for the report a bug report needs.
 *
 * Bounded and in memory: a long session leaves the last few hundred lines behind, and nothing
 * touches the disk until a person turns recording on (see [DiagnosticsSession]).
 */
class EventLog(private val limit: Int = 400) {
    private val lines = ArrayDeque<String>()

    /** Adds a line, stamped with the wall clock and tagged with the part of the app that wrote it. */
    fun add(tag: String, message: String): String {
        val line = "${STAMP.format(Instant.now())} $tag: $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > limit) lines.removeFirst()
        }
        return line
    }

    /** The lines kept so far, oldest first. */
    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }

    private companion object {
        /** Month and day are enough to line events up inside one report; the year only adds noise. */
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}

/**
 * A diagnostics session: what the app does after a person turns recording on, written line by line
 * to [file] as it happens, so a bug that kills the app still leaves its story behind.
 *
 * A session ends by itself at [limitBytes] or [limitMillis] - half an hour of a terminal app is
 * hundreds of thousands of lines for something that is read by a human - and says so in the file.
 * Nothing is written once it has ended, so a forgotten session cannot grow on the phone.
 */
class DiagnosticsSession(
    private val file: File,
    private val limitBytes: Long = LIMIT_BYTES,
    private val limitMillis: Long = LIMIT_MILLIS,
    /** True to carry on with a session a previous process left behind. */
    append: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val startedAt = clock()
    private var written = 0L
    private var ended = false

    /** Lines written so far, for the sheet that shows a recording running. */
    var lines = 0
        private set

    private val out: BufferedWriter? = runCatching {
        file.parentFile?.mkdirs()
        java.io.FileOutputStream(file, append).bufferedWriter()
    }.getOrNull()

    val running: Boolean get() = !ended && out != null

    fun elapsedMillis(): Long = clock() - startedAt

    /** Appends one line; false once the session is over, so the caller can stop asking. */
    @Synchronized
    fun line(text: String): Boolean {
        val sink = out ?: return false
        if (ended) return false
        if (clock() - startedAt > limitMillis) return refuse("the session ran its ${limitMillis / 60_000} minutes")
        val line = "$text\n"
        val size = line.toByteArray(Charsets.UTF_8).size.toLong()
        if (written + size > limitBytes) return refuse("the session filled ${limitBytes / (1024 * 1024)} MB")
        val writtenOk = runCatching {
            // Flushed per line: a crash then loses at most the line being written.
            sink.write(line)
            sink.flush()
        }.isSuccess
        if (!writtenOk) return refuse("the file could not be written")
        written += size
        lines++
        return true
    }

    /** Ends the session, saying why in the file, and closes it. Safe to call twice. */
    @Synchronized
    fun end(reason: String) {
        if (ended) return
        ended = true
        val sink = out ?: return
        runCatching {
            sink.write("— session ended: $reason\n")
            sink.close()
        }
    }

    private fun refuse(reason: String): Boolean {
        end(reason)
        return false
    }

    private companion object {
        const val LIMIT_BYTES = 4L * 1024 * 1024
        const val LIMIT_MILLIS = 30L * 60_000
    }
}

/** One labelled line of a report: what it is, and what it says. */
data class ReportedFact(val label: String, val value: String)

/** A named stretch of the log: the last few hours, or a recording someone made. */
data class ReportSection(val name: String, val lines: List<String>)

/**
 * The file a person attaches to a bug report: what the app and the phone are, what it was doing, and
 * what happened before it went wrong.
 *
 * The addresses and names the person's hosts go by are replaced before anything is written: a
 * tailnet name or a LAN address says who they are and nothing about the bug. Their shape stays, so
 * a report still says whether a host was on the local network or on the tailnet.
 */
class DiagnosticReport(
    private val title: String,
    private val facts: List<ReportedFact>,
    private val sections: List<ReportSection>,
    /** Addresses, host names and labels that must not leave the phone. */
    private val secrets: List<String>,
) {
    /**
     * The report, kept to [limit] characters of log when a session has run for a while: a file
     * travels happily at a few megabytes, a paste into an issue box does not.
     */
    fun text(limit: Int = Int.MAX_VALUE): String {
        val head = StringBuilder()
        head.appendLine(masked(title))
        head.appendLine()
        for (fact in facts) head.appendLine("${fact.label}: ${masked(fact.value)}")
        head.appendLine()
        val log = log()
        if (head.length + log.length <= limit) return head.append(log).toString()

        // The end of the log is the part nearest the bug, so the oldest lines are what goes.
        val room = (limit - head.length - NOTE_ROOM).coerceAtLeast(0)
        val kept = log.substring(log.length - room.coerceAtMost(log.length))
        val dropped = log.substring(0, log.length - kept.length)
        val lines = dropped.count { it == '\n' }
        val from = kept.indexOf('\n').let { if (it < 0) 0 else it + 1 }
        head.appendLine("  ($lines earlier lines left out)")
        head.append(kept.substring(from))
        return head.toString()
    }

    private fun log(): String = buildString {
        if (sections.all { it.lines.isEmpty() }) {
            appendLine("events:")
            appendLine("  (none)")
            return@buildString
        }
        for (section in sections) {
            if (section.lines.isEmpty()) continue
            appendLine("${section.name}:")
            for (line in section.lines) appendLine("  ${masked(line)}")
        }
    }

    private fun masked(text: String): String {
        var out = IPV4.replace(text) { "${it.groupValues[1]}.${it.groupValues[2]}.x.x" }
        out = TAILNET_NAME.replace(out) { "$HIDDEN.${it.groupValues[1]}.net" }
        // Longest first: one host's address can be the start of another's.
        for (secret in secrets.filter { it.length >= MIN_SECRET }.sortedByDescending { it.length }) {
            out = out.replace(secret, HIDDEN)
        }
        return out
    }

    private companion object {
        const val HIDDEN = "<hidden>"
        const val MIN_SECRET = 3

        /** Room for the note that says lines were left out. */
        const val NOTE_ROOM = 64

        /** Keeps the network the address belongs to, which is what says LAN or public. */
        val IPV4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}\b""")

        /** A MagicDNS name is the person's node name; the tailnet's suffix says nothing about them. */
        val TAILNET_NAME = Regex("""\b(?:[A-Za-z0-9][A-Za-z0-9-]*\.)+(ts|tailscale)\.net\b""")
    }
}
