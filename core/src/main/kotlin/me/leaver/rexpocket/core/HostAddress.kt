package me.leaver.rexpocket.core

/**
 * A host as typed on the phone. The scheme, the port and whether the address lives on the tailnet
 * all follow from the address itself, so adding a host is one field and no switches.
 */
data class HostAddress(
    val host: String,
    val port: Int,
    val plaintext: Boolean,
    val viaTailscale: Boolean,
) {
    fun endpoint(): Endpoint = Endpoint(host, port, plaintext)
}

object HostAddressParser {
    /** Rex's own port for a plaintext connection. */
    const val PLAIN_PORT = 7297

    /** TLS below this port is what a tailnet host exposed over HTTPS answers on. */
    const val TLS_PORT = 443

    private val SCHEMES = listOf("https", "http", "wss", "ws")

    /**
     * Reads `host`, `host:port` or a whole URL. A name under `.ts.net`, a `100.64/10` address or a
     * Tailscale IPv6 address is reached through the built-in node, and then TLS on 443 is assumed
     * unless the address names another port.
     */
    fun parse(raw: String): HostAddress? {
        var text = raw.trim()
        if (text.isEmpty()) return null

        var scheme: String? = null
        val lower = text.lowercase()
        for (candidate in SCHEMES) {
            val prefix = "$candidate://"
            if (lower.startsWith(prefix)) {
                scheme = candidate
                text = text.substring(prefix.length)
                break
            }
        }
        text = text.substringBefore('/').substringBefore('?').trim()
        if (text.isEmpty()) return null

        val (host, portText) = splitHostPort(text) ?: return null
        val explicitPort = portText?.let { it.toIntOrNull()?.takeIf { port -> port in 1..65535 } }
        if (portText != null && explicitPort == null) return null

        val tailnet = isTailnet(host)
        val plaintext = when {
            scheme != null -> scheme == "http" || scheme == "ws"
            explicitPort != null -> explicitPort != TLS_PORT
            else -> !tailnet
        }
        val port = explicitPort ?: if (plaintext) PLAIN_PORT else TLS_PORT
        return HostAddress(host, port, plaintext, tailnet)
    }

    /** A MagicDNS name, a `100.64/10` address or one of the Tailscale IPv6 addresses. */
    fun isTailnet(host: String): Boolean {
        val name = host.trim().trim('[', ']').lowercase()
        if (name.endsWith(".ts.net")) return true
        if (name.startsWith("fd7a:115c:a1e0")) return true
        val parts = name.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    /** Splits `host:port`, keeping an IPv6 literal in brackets as the host. */
    private fun splitHostPort(text: String): Pair<String, String?>? {
        if (text.startsWith('[')) {
            val close = text.indexOf(']')
            if (close <= 1) return null
            val host = text.substring(0, close + 1)
            val rest = text.substring(close + 1)
            return when {
                rest.isEmpty() -> host to null
                rest.startsWith(':') -> host to rest.substring(1)
                else -> null
            }
        }
        val colon = text.lastIndexOf(':')
        if (colon > 0) {
            val tail = text.substring(colon + 1)
            if (tail.isNotEmpty() && tail.all { it.isDigit() }) return text.substring(0, colon) to tail
            // One colon that is not a port is a mistyped host:port; several mean a bare IPv6 literal.
            if (text.count { it == ':' } == 1) return null
        }
        return text to null
    }
}
