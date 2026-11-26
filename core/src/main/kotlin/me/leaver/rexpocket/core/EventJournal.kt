package me.leaver.rexpocket.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The last few hours of what the app did, written as it happens and rotated.
 *
 * A person reports a bug after it happened, not while it happens, so the record cannot start when
 * they open the diagnostics sheet: it runs all along, keeps a bounded tail on the phone, and the
 * report takes the end of it. It never leaves the phone unmasked (see [DiagnosticReport]) and can
 * never grow: at [limitBytes] the file is rotated to `.1`, and only [backups] of those are kept.
 *
 * The app [post]s its lines: they are written on [executor], one thread, in the order they came,
 * so the main thread never waits on the disk. [flush] waits for what is queued: a crash and a
 * report call it.
 */
class EventJournal(
    private val file: File,
    private val limitBytes: Long = LIMIT_BYTES,
    private val backups: Int = BACKUPS,
    executor: Executor? = null,
) {
    private val executor: Executor by lazy {
        executor ?: Executors.newSingleThreadExecutor { r -> Thread(r, "rexpocket-journal").apply { isDaemon = true } }
    }

    /** Appends [text] on the journal's own thread, after every line posted before it. */
    fun post(text: String) {
        runCatching { executor.execute { line(text) } }
    }

    /** Waits up to [timeoutMs] for every line posted so far to be written. */
    fun flush(timeoutMs: Long = FLUSH_MS) {
        val done = CountDownLatch(1)
        if (runCatching { executor.execute { done.countDown() } }.isFailure) return
        runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    @Volatile private var out: BufferedWriter? = null
    @Volatile private var written = 0L

    /** Appends one line, rotating first when the file is full. */
    @Synchronized
    fun line(text: String) {
        val line = "$text\n"
        val size = line.toByteArray(Charsets.UTF_8).size.toLong()
        val sink = writer() ?: return
        if (written + size > limitBytes) {
            rotate()
            writer()?.let { runCatching { it.write(line); it.flush() }.onSuccess { written += size } }
            return
        }
        runCatching {
            sink.write(line)
            sink.flush()
        }.onSuccess { written += size }.onFailure { close() }
    }

    /**
     * The last [maxLines] lines, oldest first, reaching back into the rotated files when the
     * current one is shorter than that.
     */
    @Synchronized
    fun tail(maxLines: Int): List<String> {
        if (maxLines <= 0) return emptyList()
        val newest = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (newest.size >= maxLines) return newest.takeLast(maxLines)
        val older = ArrayList<String>()
        for (index in 1..backups) {
            val previous = runCatching { backup(index).readLines() }.getOrDefault(emptyList())
            older.addAll(0, previous)
            if (older.size + newest.size >= maxLines) break
        }
        return (older + newest).takeLast(maxLines)
    }

    private fun backup(index: Int) = File(file.parentFile, "${file.name}.$index")

    private fun writer(): BufferedWriter? {
        out?.let { return it }
        val opened = runCatching {
            file.parentFile?.mkdirs()
            if (written == 0L) written = file.length()
            FileOutputStream(file, true).bufferedWriter()
        }.getOrNull() ?: return null
        out = opened
        return opened
    }

    /** Moves the full file aside, dropping the oldest one: the tail is what a report wants. */
    private fun rotate() {
        close()
        runCatching {
            if (backups <= 0) {
                file.delete()
                return@runCatching
            }
            backup(backups).delete()
            for (index in backups - 1 downTo 1) backup(index).renameTo(backup(index + 1))
            file.renameTo(backup(1))
        }
        written = 0
    }

    private fun close() {
        val sink = out ?: return
        out = null
        runCatching { sink.close() }
    }

    private companion object {
        const val LIMIT_BYTES = 1L * 1024 * 1024
        const val BACKUPS = 1
        const val FLUSH_MS = 1_000L
    }
}
