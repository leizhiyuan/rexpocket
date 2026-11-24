package me.leaver.rexpocket.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Base64

/**
 * Host lists the phone can import, with the host key each host is expected to show, so the first
 * connection is checked against it instead of asking. Two sources go through the same steps - read
 * and check everything, plan, show the plan, apply on one confirm:
 *
 * - a `rexpocket-hosts/1` file ([HostListFormat]), written by any tool that knows the hosts (a
 *   fleet's provisioning, or RexPocket's own Export hosts);
 * - the host list of a Rex the phone is connected to (`hosts.list`, [RexHostList]).
 *
 * This file reads, checks and plans; it changes nothing. See docs/design/host-import.md.
 */
object HostListFormat {
    const val FORMAT = "rexpocket-hosts/1"

    /** What a share is looked for, to tell a host list from any other text or JSON. */
    const val FORMAT_MARKER = "rexpocket-hosts/"
    const val MAX_BYTES = 64 * 1024
    const val MAX_HOSTS = 50
    const val MAX_NAME = 64
    const val MAX_KEY_COMMENT = 256

    /** The most other addresses an entry's `otherUris` may hold. */
    const val MAX_OTHER_URIS = 16

    /** The longest tailnet account name an entry may name. */
    const val MAX_ACCOUNT = 64

    private val NAME = Regex("[A-Za-z0-9._-]{1,$MAX_NAME}")

    /** `SHA256:` and 43 characters of base64 without padding: 32 bytes, as [SshFingerprint] writes it. */
    private val FINGERPRINT = Regex("SHA256:[A-Za-z0-9+/]{43}")

    /** Whether [text] looks like a host list, of any version: worth opening the import for. */
    fun mentionsFormat(text: String): Boolean =
        // JSON may write the slash escaped, as Android's own JSON does.
        text.contains(FORMAT_MARKER) || text.contains(FORMAT_MARKER.replace("/", "\\/"))

    fun validName(name: String): Boolean = NAME.matches(name)

    /** Reads [bytes], refusing more than [MAX_BYTES] before decoding them. */
    fun parse(bytes: ByteArray): HostImportParse {
        if (bytes.size > MAX_BYTES) return fail(ImportProblem.Kind.TooLarge)
        return parse(String(bytes, Charsets.UTF_8))
    }

    fun parse(text: String): HostImportParse {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return fail(ImportProblem.Kind.TooLarge)
        val root = try {
            JSONTokener(text.trim()).nextValue() as? JSONObject
        } catch (e: JSONException) {
            null
        } ?: return fail(ImportProblem.Kind.NotJson)
        return read(root, HostListRules())
    }

    /**
     * Reads a host list already parsed as JSON: a file's root, or the list a settings backup
     * embeds (settings-backup.md), which [rules] widen. Everything is checked before anything is
     * returned; one bad entry fails the list.
     */
    internal fun read(root: JSONObject, rules: HostListRules): HostImportParse {
        val format = root.opt("format")
        if (format == null || format == JSONObject.NULL) return fail(ImportProblem.Kind.MissingFormat)
        if (format != FORMAT) return fail(ImportProblem.Kind.WrongFormat, detail = format.toString().take(80))
        val list = root.opt("hosts") as? JSONArray ?: return fail(ImportProblem.Kind.NoHosts)
        if (list.length() == 0 && !rules.mayBeEmpty) return fail(ImportProblem.Kind.NoHosts)
        if (list.length() > rules.maxHosts) return fail(ImportProblem.Kind.TooManyHosts, detail = list.length().toString())

        val hosts = ArrayList<ImportedHost>(list.length())
        for (i in 0 until list.length()) {
            val entry = i + 1
            val o = list.opt(i) as? JSONObject ?: return fail(ImportProblem.Kind.NotAnEntry, entry)
            val name = o.opt("name") as? String
            if (name == null || !rules.validName(name)) return fail(ImportProblem.Kind.BadName, entry, detail = name?.take(80))
            fun problem(kind: ImportProblem.Kind, field: String? = null) = fail(kind, entry, name, field)
            if (hosts.any { it.name.equals(name, ignoreCase = true) }) return problem(ImportProblem.Kind.DuplicateName)
            for (field in listOf("uri", "dnsUri")) if (!o.isStringOrNull(field)) return problem(ImportProblem.Kind.BadUri, field)
            val uriText = o.stringOrNull("uri")
            val dnsText = o.stringOrNull("dnsUri")
            if (uriText == null && dnsText == null) return problem(ImportProblem.Kind.NoAddress)
            val uri = uriText?.let { raw -> readUri(raw, rules).let { it.address ?: return problem(it.problem!!, "uri") } }
            val dns = dnsText?.let { raw -> readUri(raw, rules).let { it.address ?: return problem(it.problem!!, "dnsUri") } }

            if (!o.isStringOrNull("hostKeyFingerprint")) return problem(ImportProblem.Kind.BadFingerprint)
            val fingerprint = o.stringOrNull("hostKeyFingerprint")
            if (fingerprint != null && !validFingerprint(fingerprint)) return problem(ImportProblem.Kind.BadFingerprint)
            // A host key belongs to an SSH address: a Rex port has none to pin.
            if (fingerprint != null && !(dns ?: uri!!).ssh) return problem(ImportProblem.Kind.BadFingerprint)

            if (!o.isStringOrNull("keyComment")) return problem(ImportProblem.Kind.BadKeyComment)
            val comment = o.stringOrNull("keyComment")
            if (comment != null && (comment.length > MAX_KEY_COMMENT || comment.any { it.isISOControl() })) {
                return problem(ImportProblem.Kind.BadKeyComment)
            }

            // `otherUris`, when there, is every other address in order (it came after `uri`, which
            // holds only the first); a file without it keeps `uri` beside `dnsUri` as before.
            val others = o.opt("otherUris")
            val otherList = if (others == null || others == JSONObject.NULL) null else {
                val arr = others as? JSONArray ?: return problem(ImportProblem.Kind.BadUri, "otherUris")
                if (arr.length() > MAX_OTHER_URIS) return problem(ImportProblem.Kind.BadUri, "otherUris")
                List(arr.length()) { j ->
                    val raw = arr.opt(j) as? String ?: return problem(ImportProblem.Kind.BadUri, "otherUris")
                    readUri(raw, rules).let { it.address ?: return problem(it.problem!!, "otherUris") }
                }
            }
            val tryOthers = o.opt("tryOthers").let { v ->
                if (v == null || v == JSONObject.NULL) null else v as? Boolean ?: return problem(ImportProblem.Kind.BadOption, "tryOthers")
            }
            val account = o.opt("tailnetAccount").let { v ->
                if (v == null || v == JSONObject.NULL) {
                    null
                } else {
                    val t = v as? String ?: return problem(ImportProblem.Kind.BadOption, "tailnetAccount")
                    if (t.isBlank() || t.length > MAX_ACCOUNT || t.any { it.isISOControl() }) return problem(ImportProblem.Kind.BadOption, "tailnetAccount")
                    t.trim()
                }
            }

            // The name is the address to connect by; the bare address is kept beside it.
            val primary = dns ?: uri!!
            val fallbacks = (otherList ?: listOfNotNull(uri))
                .filter { !it.sameAs(primary) }
                .fold(emptyList<ImportAddress>()) { acc, a -> if (acc.any { it.sameAs(a) }) acc else acc + a }
            hosts += ImportedHost(name, primary, fallbacks, fingerprint, comment?.takeIf { it.isNotBlank() }, tryOthers, account)
        }
        return HostImportParse.Parsed(hosts)
    }

    private fun readUri(raw: String, rules: HostListRules): UriRead {
        val scheme = raw.trim().substringBefore("://", missingDelimiterValue = "").lowercase()
        return if (rules.rexPorts && (scheme == "ws" || scheme == "wss")) readRexUri(raw) else readSshUri(raw)
    }

    /**
     * `ws://host[:port]` or `wss://host[:port]`, Rex's own port, which only a settings backup may
     * hold: no account, password, path, query or fragment.
     */
    internal fun readRexUri(raw: String): UriRead {
        fun bad(kind: ImportProblem.Kind) = UriRead(null, kind)
        val text = raw.trim()
        val rest = text.substringAfter("://")
        if (rest.contains('?')) return bad(ImportProblem.Kind.UriHasQuery)
        if (rest.contains('#') || rest.trimEnd('/').contains('/')) return bad(ImportProblem.Kind.UriHasPath)
        if (rest.contains('@')) return bad(ImportProblem.Kind.UriHasPassword)
        val hostPort = rest.trimEnd('/')
        val portText = if (hostPort.startsWith('[')) {
            hostPort.substringAfter(']', "").takeIf { it.startsWith(':') }?.substring(1)
        } else {
            hostPort.substringAfterLast(':', "").takeIf { hostPort.contains(':') }
        }
        if (portText != null && (portText.toIntOrNull() ?: 0) !in 1..65535) return bad(ImportProblem.Kind.BadPort)
        val parsed = HostAddressParser.parse(text) ?: return bad(ImportProblem.Kind.BadUri)
        if (parsed.viaSsh || parsed.host.isBlank()) return bad(ImportProblem.Kind.BadUri)
        return UriRead(ImportAddress(null, parsed.host, parsed.port, parsed.plaintext), null)
    }

    /**
     * Writes [hosts] as a `rexpocket-hosts/1` file, which [parse] reads back: public data only, no
     * password or key ever. Only SSH hosts whose name the format allows go in; the others are
     * returned in [HostListExport.left].
     */
    fun write(hosts: List<ExportedHost>): HostListExport {
        val (root, export) = writeList(hosts, HostListRules())
        return export.copy(text = root.toString(2))
    }

    /**
     * The host list as JSON, and what went in and what was left out: [write]'s, or the wider list
     * a settings backup embeds ([rules]: Rex ports and Rex's names too, more hosts). A second host
     * of the same name is left out: the name is what an import matches by.
     */
    internal fun writeList(hosts: List<ExportedHost>, rules: HostListRules): Pair<JSONObject, HostListExport> {
        val seen = HashSet<String>()
        val why = LinkedHashMap<String, HostListExport.Reason>()
        val fit = hosts.filter { h ->
            val reason = when {
                !h.address.ssh && !rules.rexPorts -> HostListExport.Reason.NotSsh
                !rules.validName(h.name) -> HostListExport.Reason.BadName
                !seen.add(h.name.lowercase()) -> HostListExport.Reason.SameName
                else -> null
            }
            if (reason != null) why.putIfAbsent(h.name, reason)
            reason == null
        }
        val list = JSONArray()
        val written = fit.take(rules.maxHosts)
        fit.drop(rules.maxHosts).forEach { why.putIfAbsent(it.name, HostListExport.Reason.TooMany) }
        written.forEach { h ->
            val others = h.fallbacks.filter { (it.ssh || rules.rexPorts) && !it.sameAs(h.address) }
                .fold(emptyList<ImportAddress>()) { acc, a -> if (acc.any { it.sameAs(a) }) acc else acc + a }
                .take(MAX_OTHER_URIS)
            list.put(
                JSONObject()
                    .put("name", h.name)
                    // The first other address, for a reader from before `otherUris`.
                    .put("uri", others.firstOrNull()?.toString() ?: JSONObject.NULL)
                    .put("dnsUri", h.address.toString())
                    .put("hostKeyFingerprint", h.fingerprint?.takeIf { h.address.ssh && validFingerprint(it) } ?: JSONObject.NULL)
                    .put("keyComment", h.keyComment?.takeIf { c -> c.isNotBlank() && c.length <= MAX_KEY_COMMENT && c.none { it.isISOControl() } } ?: JSONObject.NULL)
                    .put("otherUris", JSONArray(others.map { it.toString() }))
                    .put("tryOthers", h.tryOthers)
                    .apply {
                        h.tailnetAccount?.trim()?.takeIf { a -> a.isNotEmpty() && a.length <= MAX_ACCOUNT && a.none { it.isISOControl() } }
                            ?.let { put("tailnetAccount", it) }
                    },
            )
        }
        val root = JSONObject().put("format", FORMAT).put("hosts", list)
        return root to HostListExport("", written.map { it.name }, why.keys.toList(), why)
    }

    /** Exactly what [SshFingerprint] shows: no padding, and the base64 decodes to 32 bytes and back. */
    fun validFingerprint(text: String): Boolean {
        if (!FINGERPRINT.matches(text)) return false
        val b64 = text.removePrefix("SHA256:")
        val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return false
        return bytes.size == 32 && Base64.getEncoder().withoutPadding().encodeToString(bytes) == b64
    }

    internal class UriRead(val address: ImportAddress?, val problem: ImportProblem.Kind?)

    /**
     * `ssh://user@host[:port]` and nothing more. [HostAddressParser] would quietly drop a path or a
     * query and take `user:secret` as an account, so those are refused here first.
     */
    internal fun readSshUri(raw: String): UriRead {
        fun bad(kind: ImportProblem.Kind) = UriRead(null, kind)
        val text = raw.trim()
        val scheme = text.substringBefore("://", missingDelimiterValue = "")
        if (scheme.isEmpty()) return bad(ImportProblem.Kind.BadUri)
        if (!scheme.equals("ssh", ignoreCase = true)) return bad(ImportProblem.Kind.NotSsh)
        val rest = text.substring(scheme.length + 3)
        if (rest.contains('?')) return bad(ImportProblem.Kind.UriHasQuery)
        if (rest.contains('#') || rest.contains('/')) return bad(ImportProblem.Kind.UriHasPath)
        val at = rest.lastIndexOf('@')
        if (at > 0 && rest.substring(0, at).contains(':')) return bad(ImportProblem.Kind.UriHasPassword)
        if (at <= 0) return bad(ImportProblem.Kind.UriNoUser)
        val hostPort = rest.substring(at + 1)
        val portText = if (hostPort.startsWith('[')) {
            hostPort.substringAfter(']', "").takeIf { it.startsWith(':') }?.substring(1)
        } else {
            hostPort.substringAfterLast(':', "").takeIf { hostPort.contains(':') }
        }
        if (portText != null && (portText.toIntOrNull() ?: 0) !in 1..65535) return bad(ImportProblem.Kind.BadPort)
        val parsed = HostAddressParser.parse(text) ?: return bad(ImportProblem.Kind.BadUri)
        if (!parsed.viaSsh || parsed.sshUser.isNullOrBlank()) return bad(ImportProblem.Kind.BadUri)
        return UriRead(ImportAddress(parsed.sshUser, parsed.host, parsed.port, plaintext = true), null)
    }

    private fun JSONObject.stringOrNull(key: String): String? = opt(key) as? String

    private fun JSONObject.isStringOrNull(key: String): Boolean = opt(key).let { it == null || it == JSONObject.NULL || it is String }
}

/**
 * What a host list may hold. A `rexpocket-hosts/1` file holds SSH hosts named as a file name would
 * be; the list a settings backup embeds keeps every host the phone has, so it also takes Rex's own
 * port (`ws://`, `wss://`), Rex's rule for a name, no hosts at all, and more of them. See
 * settings-backup.md.
 */
internal data class HostListRules(
    val maxHosts: Int = HostListFormat.MAX_HOSTS,
    val rexPorts: Boolean = false,
    val rexNames: Boolean = false,
    val mayBeEmpty: Boolean = false,
) {
    fun validName(name: String): Boolean =
        if (rexNames) RexHostList.validLabel(name) else HostListFormat.validName(name)

    companion object {
        /** The list a settings backup embeds. */
        val BACKUP = HostListRules(maxHosts = 200, rexPorts = true, rexNames = true, mayBeEmpty = true)
    }
}

/**
 * The host list of a Rex the phone is connected to, from `hosts.list`: other Rex servers the desktop
 * reaches by label. Each endpoint is read as the app reads an address it is given; one the phone
 * cannot use (a Unix socket or named pipe, an `ssh://` with no account) is left out, and a host with
 * none left is shown as skipped. Rex sends no host keys, so the first SSH connection asks, as today.
 */
object RexHostList {
    const val MAX_LABEL = 64

    fun read(payload: JSONObject): HostImportParse {
        val list = payload.optJSONArray("hosts") ?: JSONArray()
        if (list.length() > HostListFormat.MAX_HOSTS) return fail(ImportProblem.Kind.TooManyHosts, detail = list.length().toString())
        val hosts = ArrayList<ImportedHost>()
        val skipped = ArrayList<SkippedHost>()
        for (i in 0 until list.length()) {
            val entry = i + 1
            val o = list.optJSONObject(i) ?: return fail(ImportProblem.Kind.NotAnEntry, entry)
            val label = o.optString("label")
            if (!validLabel(label)) {
                return fail(ImportProblem.Kind.BadName, entry, detail = label.take(80))
            }
            if (hosts.any { it.name.equals(label, ignoreCase = true) }) return fail(ImportProblem.Kind.DuplicateName, entry, label)
            val endpoints = o.optJSONArray("endpoints") ?: JSONArray()
            val usable = (0 until endpoints.length()).mapNotNull { usable(endpoints.optString(it)) }
                .fold(emptyList<ImportAddress>()) { acc, a -> if (acc.any { it.sameAs(a) }) acc else acc + a }
            if (usable.isEmpty()) {
                skipped += SkippedHost(label, SkippedHost.Reason.NoUsableEndpoint)
                continue
            }
            hosts += ImportedHost(label, usable.first(), usable.drop(1), fingerprint = null, keyComment = null)
        }
        return HostImportParse.Parsed(hosts, skipped)
    }

    /** Rex's rule for a host's label: 1-64 characters, no surrounding spaces, no control characters. */
    fun validLabel(label: String): Boolean =
        label.isNotBlank() && label == label.trim() && label.length <= MAX_LABEL && label.none { it.isISOControl() }

    /** An endpoint as the phone can reach it, or null: a socket path works only on its own machine. */
    fun usable(endpoint: String): ImportAddress? {
        val text = endpoint.trim()
        val lower = text.lowercase()
        if (text.isEmpty() || lower.startsWith("unix:") || lower.startsWith("npipe:") || text.startsWith("/")) return null
        if (lower.startsWith("ssh://")) return HostListFormat.readSshUri(text).address
        val scheme = lower.substringBefore("://", "")
        if (scheme.isNotEmpty() && scheme !in setOf("http", "https", "ws", "wss")) return null
        val parsed = HostAddressParser.parse(text) ?: return null
        if (parsed.viaSsh) return null
        return ImportAddress(null, parsed.host, parsed.port, parsed.plaintext)
    }
}

private fun fail(
    kind: ImportProblem.Kind,
    entry: Int? = null,
    name: String? = null,
    field: String? = null,
    detail: String? = null,
) = HostImportParse.Failed(ImportProblem(kind, entry, name, field, detail))

/**
 * Where a host is reached: `ssh://user@host:port` when [user] is set, else Rex's own port, plaintext
 * or TLS. The host compares without case, as DNS does.
 */
data class ImportAddress(val user: String?, val host: String, val port: Int, val plaintext: Boolean) {
    val ssh: Boolean get() = user != null

    fun sameAs(other: ImportAddress?): Boolean =
        other != null && user == other.user && host.equals(other.host, ignoreCase = true) && port == other.port &&
            (ssh || plaintext == other.plaintext)

    /** `ssh://user@host:port`, `ws://host:port` or `wss://host:port`, which [parse] reads back. */
    override fun toString(): String =
        if (ssh) "ssh://$user@$host:$port" else "${if (plaintext) "ws" else "wss"}://$host:$port"

    companion object {
        fun parse(text: String): ImportAddress? {
            val parsed = HostAddressParser.parse(text) ?: return null
            if (parsed.viaSsh && parsed.sshUser.isNullOrBlank()) return null
            return ImportAddress(parsed.sshUser.takeIf { parsed.viaSsh }, parsed.host, parsed.port, parsed.plaintext)
        }
    }
}

/** One host to import, checked. [fingerprint] null leaves the first connection to ask, as today. */
data class ImportedHost(
    val name: String,
    /** The address to connect by: a file's `dnsUri` when it has one, else its `uri`; Rex's first endpoint. */
    val address: ImportAddress,
    /** The other addresses, in the order to try them: a file's `uri` beside its `dnsUri`; Rex's other endpoints. */
    val fallbacks: List<ImportAddress>,
    val fingerprint: String?,
    val keyComment: String?,
    /** Try Other addresses; null leaves the host's choice as it is (a file from before the field). */
    val tryOthers: Boolean? = null,
    /** The tailnet account the host is on, by its name; null leaves the host's as it is. */
    val tailnetAccount: String? = null,
) {
    /** The comment of the key the phone makes for this host. */
    val commentForKey: String get() = keyComment ?: "rexpocket:$name"
}

/** A host of the source that is not imported, and why: it is shown in the preview, not an error. */
data class SkippedHost(val name: String, val reason: Reason) {
    enum class Reason { NoUsableEndpoint }
}

/** A host as Export hosts writes it. */
data class ExportedHost(
    val name: String,
    val address: ImportAddress,
    /** Every other address, in the order a connection tries them. */
    val fallbacks: List<ImportAddress>,
    val fingerprint: String?,
    val keyComment: String?,
    val tryOthers: Boolean = true,
    /** The name of the tailnet account the host names; null when it follows the active one. */
    val tailnetAccount: String? = null,
)

/**
 * A written host list, the names in it, and the names left out, each with why ([reasons]): not an
 * SSH host, a name the format refuses, the name of a host already written, or past the most hosts.
 */
data class HostListExport(
    val text: String,
    val written: List<String>,
    val left: List<String>,
    val reasons: Map<String, Reason> = emptyMap(),
) {
    enum class Reason { NotSsh, BadName, SameName, TooMany }

    /** The names left out for [reason], in the phone's order. */
    fun leftFor(reason: Reason): List<String> = left.filter { reasons[it] == reason }
}

/** Why a list cannot be imported. [entry] counts from 1, as a person reading the file does. */
data class ImportProblem(
    val kind: Kind,
    val entry: Int? = null,
    val name: String? = null,
    val field: String? = null,
    val detail: String? = null,
) {
    enum class Kind {
        TooLarge, NotJson, MissingFormat, WrongFormat, NoHosts, TooManyHosts, NotAnEntry, BadName,
        DuplicateName, NoAddress, BadUri, NotSsh, UriHasPassword, UriHasPath, UriHasQuery, UriNoUser,
        BadPort, BadFingerprint, BadKeyComment,

        /** A `tryOthers` or `tailnetAccount` that is not what the field holds. */
        BadOption,

        /** Planning: two of the phone's hosts fit one entry, or one of them fits two entries. */
        AmbiguousMatch,
    }

    /** English, for a test or a log line: names and counts only, never key material. */
    override fun toString(): String =
        listOfNotNull(kind.name, entry?.let { "entry $it" }, name, field, detail).joinToString(" · ")
}

sealed interface HostImportParse {
    data class Parsed(val hosts: List<ImportedHost>, val skipped: List<SkippedHost> = emptyList()) : HostImportParse
    data class Failed(val problem: ImportProblem) : HostImportParse
}

/**
 * A host the phone has, as far as an import needs to know it. [hasCredentials] is false for an SSH
 * host with neither password nor key.
 */
data class KnownHost(
    val id: String,
    val name: String,
    val address: ImportAddress,
    val fallbacks: List<ImportAddress>,
    val hostKey: String?,
    val hasCredentials: Boolean,
    val tryOthers: Boolean = true,
    /** The name of the tailnet account it names; null when it follows the active one. */
    val tailnetAccount: String? = null,
)

/** What the import does with an entry's host key. */
sealed interface KeyChange {
    /** The source names none: the pin, or the first connection's prompt, stays as it is. */
    data object Kept : KeyChange

    /** The same key is pinned already. */
    data object Same : KeyChange

    /** Nothing was pinned; this one will be, so the first connection is checked against it. */
    data class Set(val new: String) : KeyChange

    /** A different key is pinned. Replaced only when the person confirms this host on its own. */
    data class Replaced(val old: String, val new: String) : KeyChange
}

enum class ImportStatus { New, Unchanged, Changed }

enum class ImportField { Name, Address, Fallbacks, TryOthers, TailnetAccount }

data class FieldChange(val field: ImportField, val old: String?, val new: String?)

data class PlannedHost(
    val imported: ImportedHost,
    /** The phone's host this entry updates, null for a new one. */
    val existing: KnownHost?,
    val changes: List<FieldChange>,
    val key: KeyChange,
) {
    val status: ImportStatus = when {
        existing == null -> ImportStatus.New
        changes.isEmpty() && (key == KeyChange.Kept || key == KeyChange.Same) -> ImportStatus.Unchanged
        else -> ImportStatus.Changed
    }

    /** A pinned key would be replaced: this host needs its own confirmation. */
    val keyConflict: Boolean get() = key is KeyChange.Replaced

    /**
     * It gets a fresh key of its own: an SSH host that is new, or the phone's but with nothing to
     * sign in with. Never another host's key.
     */
    val needsKey: Boolean get() = imported.address.ssh && (existing == null || !existing.hasCredentials || !existing.address.ssh)

    /** The host key pinned after the import, given the one pinned now. */
    fun pinnedKey(current: String): String = when (val k = key) {
        KeyChange.Kept, KeyChange.Same -> current
        is KeyChange.Set -> k.new
        is KeyChange.Replaced -> k.new
    }
}

/** What a confirmed import writes: the hosts to change, the hosts to add, and the ones held back. */
data class ImportResult(
    val updates: List<PlannedHost>,
    val creates: List<PlannedHost>,
    /** Hosts whose key change was not confirmed: left exactly as they were. */
    val held: List<PlannedHost>,
    val unchanged: List<PlannedHost>,
) {
    /** For the log: counts and host names only. */
    fun summary(): String = "import: ${creates.size} new (${creates.joinToString { it.imported.name }}), " +
        "${updates.size} changed (${updates.joinToString { it.imported.name }}), ${unchanged.size} unchanged, " +
        "${held.size} held (${held.joinToString { it.imported.name }})"
}

data class ImportPlan(val hosts: List<PlannedHost>, val skipped: List<SkippedHost> = emptyList()) {
    val conflicts: List<PlannedHost> get() = hosts.filter { it.keyConflict }

    /**
     * The one confirm. A host whose pinned key would change goes in only when its name is in
     * [confirmedKeyChanges]; otherwise it is held back whole, its other changes too. Nothing is ever
     * removed: hosts not in the source are not in the plan at all.
     */
    fun confirm(confirmedKeyChanges: Set<String>): ImportResult {
        val held = hosts.filter { it.keyConflict && it.imported.name !in confirmedKeyChanges }
        val go = hosts.filter { it !in held }
        return ImportResult(
            updates = go.filter { it.status == ImportStatus.Changed },
            creates = go.filter { it.status == ImportStatus.New },
            held = held,
            unchanged = go.filter { it.status == ImportStatus.Unchanged },
        )
    }

    companion object {
        /**
         * Matches each entry to a host the phone has: first by name (ignoring case), then by address
         * (the entry's address or a fallback against the host's address or a fallback). More than
         * one candidate, or one host fitting two entries, fails the whole plan.
         */
        fun of(parsed: HostImportParse.Parsed, known: List<KnownHost>): HostImportPlan {
            val taken = HashMap<String, String>()
            val planned = parsed.hosts.mapIndexed { index, host ->
                val entry = index + 1
                val byName = known.filter { it.name.equals(host.name, ignoreCase = true) }
                val candidates = byName.ifEmpty {
                    val mine = listOf(host.address) + host.fallbacks
                    known.filter { k -> (listOf(k.address) + k.fallbacks).any { a -> mine.any { it.sameAs(a) } } }
                }
                if (candidates.size > 1) {
                    return HostImportPlan.Failed(
                        ImportProblem(ImportProblem.Kind.AmbiguousMatch, entry, host.name, detail = candidates.joinToString { it.name }),
                    )
                }
                val match = candidates.singleOrNull()
                if (match != null) {
                    taken.put(match.id, host.name)?.let { other ->
                        return HostImportPlan.Failed(
                            ImportProblem(ImportProblem.Kind.AmbiguousMatch, entry, host.name, detail = "${match.name} = $other"),
                        )
                    }
                }
                plan(host, match)
            }
            return HostImportPlan.Planned(ImportPlan(planned, parsed.skipped))
        }

        private fun plan(host: ImportedHost, existing: KnownHost?): PlannedHost {
            if (existing == null) {
                return PlannedHost(host, null, emptyList(), host.fingerprint?.let { KeyChange.Set(it) } ?: KeyChange.Kept)
            }
            val changes = buildList {
                if (existing.name != host.name) add(FieldChange(ImportField.Name, existing.name, host.name))
                if (!host.address.sameAs(existing.address)) {
                    add(FieldChange(ImportField.Address, existing.address.toString(), host.address.toString()))
                }
                // A source without fallbacks leaves the host's as they are: it never removes anything.
                val same = host.fallbacks.size == existing.fallbacks.size &&
                    host.fallbacks.zip(existing.fallbacks).all { (a, b) -> a.sameAs(b) }
                if (host.fallbacks.isNotEmpty() && !same) {
                    add(
                        FieldChange(
                            ImportField.Fallbacks,
                            existing.fallbacks.joinToString(", ").ifEmpty { null },
                            host.fallbacks.joinToString(", "),
                        ),
                    )
                }
                if (host.tryOthers != null && host.tryOthers != existing.tryOthers) {
                    add(FieldChange(ImportField.TryOthers, existing.tryOthers.toString(), host.tryOthers.toString()))
                }
                if (host.tailnetAccount != null && !host.tailnetAccount.equals(existing.tailnetAccount, ignoreCase = true)) {
                    add(FieldChange(ImportField.TailnetAccount, existing.tailnetAccount, host.tailnetAccount))
                }
            }
            val pinned = existing.hostKey?.takeIf { it.isNotBlank() }
            val key = when {
                host.fingerprint == null -> KeyChange.Kept
                pinned == null -> KeyChange.Set(host.fingerprint)
                pinned == host.fingerprint -> KeyChange.Same
                else -> KeyChange.Replaced(pinned, host.fingerprint)
            }
            return PlannedHost(host, existing, changes, key)
        }
    }
}

sealed interface HostImportPlan {
    data class Planned(val plan: ImportPlan) : HostImportPlan
    data class Failed(val problem: ImportProblem) : HostImportPlan
}

/**
 * Writes [this] result onto the phone's [hosts], whatever the app keeps them as: [update] changes one
 * in place, [create] makes a new one (with its fresh key). Every host the result does not name is
 * kept as it was, in its place; none is removed.
 */
fun <H> ImportResult.applyTo(
    hosts: List<H>,
    id: (H) -> String,
    update: (H, PlannedHost) -> H,
    create: (PlannedHost) -> H,
): List<H> {
    val byId = updates.associateBy { it.existing!!.id }
    return hosts.map { h -> byId[id(h)]?.let { update(h, it) } ?: h } + creates.map(create)
}
