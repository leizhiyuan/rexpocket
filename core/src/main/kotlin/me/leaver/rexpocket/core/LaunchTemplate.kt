package me.leaver.rexpocket.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A host's agent launch template: a name, a directory and a command, such as "api", `~/src/api`
 * and `claude`. Running one opens a new tab that starts in the directory and runs the command, as
 * typing `cd ~/src/api && claude` into a fresh tab would; when the command ends, the tab is a shell
 * in that directory. See docs/design/home.md, "Launch templates".
 *
 * Rex's shell block takes a `command` and a `cwd` itself (block-creators.json, the shell flavor's
 * options), so nothing is typed: the tab is made with the command already in it. The command goes
 * through `sh -c`, so it is a shell line (`claude --resume`, `npm run dev | tee log`), and `~` in
 * the directory is the host's home, which a plain `cwd` does not expand. Rex runs that `sh` through
 * the person's own shell with its startup files read (`interactive`), so the agent is found on the
 * PATH their terminals have.
 */
data class LaunchTemplate(
    val id: String,
    val name: String,
    val directory: String = "",
    val command: String = "",
) {
    /** What the tab runs: the directory, the command, then the person's shell, in `sh` syntax. */
    fun script(): String {
        val dir = directory.trim()
        val cmd = command.trim()
        val shell = "exec \"\${SHELL:-sh}\" -l"
        val cd = if (dir.isEmpty()) null else "cd ${shellPath(dir)}"
        return when {
            cd != null && cmd.isNotEmpty() -> "$cd && $cmd; $shell"
            cd != null -> "$cd; $shell"
            cmd.isNotEmpty() -> "$cmd; $shell"
            else -> shell
        }
    }

    /** The shell block of Rex's layout that starts this template. */
    fun block(): JSONObject = JSONObject()
        .put("flavor", SHELL_FLAVOR)
        .put(
            "options",
            JSONObject()
                .put("command", JSONArray().put("sh").put("-c").put(script()))
                .put("interactive", true),
        )

    /** The tab's label: the template's name, as long as Rex takes one. */
    val tabLabel: String get() = name.trim().take(MAX_LABEL)

    /** A template is worth keeping when it is named and says where to go or what to run. */
    val usable: Boolean get() = name.isNotBlank() && (directory.isNotBlank() || command.isNotBlank())

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("directory", directory).put("command", command)

    companion object {
        const val SHELL_FLAVOR = "com.superlogical.terminal.shell"
        const val MAX_ITEMS = 50
        const val MAX_NAME = 64
        const val MAX_TEXT = 1024

        /** Rex's limit on a window label. */
        const val MAX_LABEL = 128

        /**
         * [path] for `sh`: quoted, so spaces and quotes in it are kept, with a leading `~` or `~/`
         * left outside the quotes so the shell makes it the home directory.
         */
        fun shellPath(path: String): String = when {
            path == "~" -> "~"
            path.startsWith("~/") -> "~/" + quote(path.removePrefix("~/")).takeIf { path.length > 2 }.orEmpty()
            else -> quote(path)
        }

        private fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

        /** [o] as a template, or null when it is not one: a missing name, text too long. */
        fun fromJson(o: JSONObject?): LaunchTemplate? {
            o ?: return null
            val name = (o.opt("name") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: return null
            fun text(key: String): String? {
                val v = o.opt(key)
                if (v == null || v == JSONObject.NULL) return ""
                return (v as? String)?.takeIf { it.length <= MAX_TEXT && it.none { c -> c == '\u0000' } }
            }
            val directory = text("directory") ?: return null
            val command = text("command") ?: return null
            val id = (o.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: newId()
            return LaunchTemplate(id, name, directory, command).takeIf { it.usable }
        }

        fun encode(list: List<LaunchTemplate>): JSONArray = JSONArray().apply { list.forEach { put(it.toJson()) } }

        /** The templates in [arr], the ones it cannot read left out, at most [MAX_ITEMS]. */
        fun decode(arr: JSONArray?): List<LaunchTemplate> {
            arr ?: return emptyList()
            return (0 until arr.length()).mapNotNull { fromJson(arr.optJSONObject(it)) }.distinctBy { it.id }.take(MAX_ITEMS)
        }

        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}
