package me.leaver.rexpocket.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * `rexpocket://add?addr=<address>&name=<name>`: a host to add, as a link or a QR code holding one.
 * It names where the host is and what to call it, and nothing else: no password, no key, no host
 * key. Opening one never saves anything; the Add host dialog opens filled in, for the person to
 * check and save. See docs/design/host-link.md.
 */
object HostLink {
    const val SCHEME = "rexpocket"
    const val ACTION = "add"

    /** The longest link read: an address and a name, with room to spare. */
    const val MAX_LENGTH = 1024

    sealed interface Read {
        /** [address] as the host dialog's Address field takes it; [name] null when the link names none. */
        data class Ok(val address: String, val name: String?) : Read

        /** Why the link is not one to open, in English for the log (never the link itself). */
        data class Refused(val why: String) : Read
    }

    fun parse(link: String): Read {
        val text = link.trim()
        if (text.length > MAX_LENGTH) return Read.Refused("too long")
        val schemeEnd = text.indexOf("://")
        if (schemeEnd < 0 || !text.substring(0, schemeEnd).equals(SCHEME, ignoreCase = true)) return Read.Refused("not a rexpocket link")
        val rest = text.substring(schemeEnd + 3)
        val action = rest.substringBefore('?').trimEnd('/')
        if (!action.equals(ACTION, ignoreCase = true)) return Read.Refused("not an add link")
        val query = rest.substringAfter('?', "").substringBefore('#')
        val params = HashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val key = part.substringBefore('=')
            val value = runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull()
                ?: return Read.Refused("bad encoding")
            // A key given twice could hide one value behind the other: refused.
            if (params.put(key, value) != null) return Read.Refused("$key twice")
        }
        val address = params["addr"]?.trim().orEmpty()
        if (address.isEmpty()) return Read.Refused("no address")
        if (address.any { it.isISOControl() || it.isWhitespace() }) return Read.Refused("bad address")
        // No secrets: an account with a password in it is refused rather than dropped quietly.
        val authority = address.substringAfter("://").substringBefore('/')
        if (authority.contains('@') && authority.substringBeforeLast('@').contains(':')) return Read.Refused("password in address")
        if (address.substringAfter("://").contains('?') || address.substringAfter("://").contains('#')) return Read.Refused("query in address")
        val parsed = HostAddressParser.parse(address) ?: return Read.Refused("address not readable")
        if (parsed.host.isBlank()) return Read.Refused("address not readable")
        if (params["name"]?.any { it.isISOControl() } == true) return Read.Refused("bad name")
        val name = params["name"]?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null && !RexHostList.validLabel(name)) return Read.Refused("bad name")
        return Read.Ok(address, name)
    }

    /** The link for [address] and [name]: what a script or another phone puts in a QR code. */
    fun write(address: String, name: String? = null): String = buildString {
        append("$SCHEME://$ACTION?addr=").append(encode(address.trim()))
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { append("&name=").append(encode(it)) }
    }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
