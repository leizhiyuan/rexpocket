package me.leaver.rexpocket.core

import org.json.JSONObject

// Insert path: a folder of the host as Rex's `com.superlogical.terminal.list_dir` lists it, read
// for the phone's folder browser, and the path picked typed into the pane in a form the shell
// takes. See docs/design/terminal-controls.md, "Insert path".

/** Whether a folder could be read: Rex's `status`. */
enum class DirStatus { Ok, Missing, NotDir, Denied, Unknown }

/** One entry of a folder: [file] is anything that is not a folder (or a link to one). */
data class DirEntry(val name: String, val file: Boolean = false, val symlink: Boolean = false) {
    /** Dot files: listed after the rest, and only when the person asks for them. */
    val hidden: Boolean get() = name.startsWith(".")
}

/**
 * A folder as `list_dir` answered: [path] absolute even when it could not be read, [home] the
 * program's home ("" when Rex cannot tell), [entries] in the order the browser shows them
 * ([sorted]), and [truncated] when Rex left some out.
 */
data class DirListing(
    val path: String,
    val home: String,
    val status: DirStatus,
    val entries: List<DirEntry>,
    val truncated: Boolean,
) {
    /** The folder above, or null at the root. */
    val parent: String? get() = parentOf(path)

    /** The entries to show, in [sorted]'s order; dot entries only when [hidden] is asked for. */
    fun shown(hidden: Boolean): List<DirEntry> = entries.filter { hidden || !it.hidden }

    /** The full path of [entry] in this folder. */
    fun child(entry: DirEntry): String = if (path.endsWith("/")) path + entry.name else "$path/${entry.name}"

    companion object {
        /** Rex's answer, as preview.22 sends it (checked by ListDirTest on a real Rex). */
        fun parse(json: JSONObject): DirListing {
            val status = when (json.optString("status")) {
                "ok" -> DirStatus.Ok
                "missing" -> DirStatus.Missing
                "not_dir" -> DirStatus.NotDir
                "denied" -> DirStatus.Denied
                else -> DirStatus.Unknown
            }
            val raw = json.optJSONArray("entries")
            val entries = buildList {
                if (raw != null) for (i in 0 until raw.length()) {
                    val e = raw.optJSONObject(i) ?: continue
                    val name = e.optString("name").takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: continue
                    add(DirEntry(name, file = e.optBoolean("file"), symlink = e.optBoolean("symlink")))
                }
            }
            return DirListing(json.optString("path"), json.optString("home"), status, sorted(entries), json.optBoolean("truncated"))
        }

        /** Folders first, then files; by name, ignoring case; dot entries after the others of their kind. */
        fun sorted(entries: List<DirEntry>): List<DirEntry> =
            entries.sortedWith(compareBy<DirEntry>({ it.file }, { it.hidden }, { it.name.lowercase() }, { it.name }))

        fun parentOf(path: String): String? {
            val p = path.trimEnd('/')
            if (p.isEmpty()) return null
            val cut = p.lastIndexOf('/')
            return when {
                cut < 0 -> null
                cut == 0 -> "/"
                else -> p.substring(0, cut)
            }
        }
    }
}

/**
 * [path] as it is typed into a shell: left alone when every character is one no shell treats
 * specially, otherwise in single quotes (a quote inside as `'\''`). A path under [home] starts
 * with `~/`, outside the quotes so the shell still expands it: `~/'my notes'`.
 */
fun shellPath(path: String, home: String = ""): String {
    val h = home.trimEnd('/')
    if (h.isNotEmpty()) {
        if (path == h || path == "$h/") return "~"
        if (path.startsWith("$h/")) return "~/" + shellQuote(path.removePrefix("$h/"))
    }
    return shellQuote(path)
}

/** One word for a shell: as it is when safe, otherwise single-quoted. */
fun shellQuote(word: String): String {
    if (word.isNotEmpty() && word.all { it in SAFE_CHARS }) return word
    return "'" + word.replace("'", "'\\''") + "'"
}

private val SAFE_CHARS: Set<Char> =
    (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('/', '.', '_', '-', '+', ',', ':', '@', '%', '=')).toSet()
