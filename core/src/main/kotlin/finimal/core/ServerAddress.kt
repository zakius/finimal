package finimal.core

/** Jellyfin's HTTP port out of the box. */
const val DEFAULT_JELLYFIN_PORT = "8096"

/**
 * Base URLs to try, in order, for what the login form says. A scheme is used as written. Without one, try https and
 * then http on the port, except that port 443 means https only. A port written in [server] overrides [port], and an
 * empty [port] means the scheme's default. The caller must stop at a certificate problem, because falling back to
 * http would send the password in clear text.
 */
fun serverCandidates(server: String, port: String): List<String> {
    val input = server.trim().trimEnd('/')
    if (input.isEmpty()) return emptyList()
    val p = port.trim()
    val scheme = if ("://" in input) input.substringBefore("://") else null
    val rest = if (scheme != null) input.substringAfter("://") else input
    val host = rest.substringBefore('/')
    val authority = bracketIpv6(host)
    val path = rest.substring(host.length)
    fun withPort(port: String) = if (port.isEmpty()) "$authority$path" else "$authority:$port$path"
    if (scheme != null) {
        val address = if (hasPort(authority)) "$authority$path" else withPort(p)
        return listOf("$scheme://$address")
    }
    if (hasPort(authority)) return listOf("https://$authority$path", "http://$authority$path")
    if (p == "443") return listOf("https://$authority$path")
    return listOf("https://${withPort(p)}", "http://${withPort(p)}")
}

/**
 * The port to prefill for what the server field says. A home-network host (a single-label name, a local suffix or an
 * IP literal) gets Jellyfin's own port. Any other domain gets 443, since it is most likely behind a reverse proxy. The
 * user can correct the guess. Returns null when [server] already has a port.
 */
fun defaultPortFor(server: String): String? {
    val rest = server.trim().substringAfter("://").substringBefore('/')
    if (hasPort(bracketIpv6(rest))) return null
    val host = rest.removePrefix("[").substringBefore(']').lowercase()
    val singleLabel = '.' !in host
    val ipLiteral = ':' in host || host.all { it.isDigit() || it == '.' }
    val localSuffixes = listOf(".local", ".lan", ".home", ".internal", ".localhost", ".home.arpa")
    val localSuffix = localSuffixes.any { suffix -> host.endsWith(suffix) }
    val local = singleLabel || ipLiteral || localSuffix
    return if (local) DEFAULT_JELLYFIN_PORT else "443"
}

/** Splits a saved base URL into the login form's server and port fields. Nothing saved gives the default port. */
fun splitServerAddress(url: String?): Pair<String, String> {
    if (url.isNullOrBlank()) return "" to DEFAULT_JELLYFIN_PORT
    val scheme = url.substringBefore("://", "")
    val rest = url.substringAfter("://")
    val authority = rest.substringBefore('/')
    val path = rest.substring(authority.length)
    val prefix = if (scheme.isEmpty()) "" else "$scheme://"
    if (!hasPort(authority)) return "$prefix$authority$path" to ""
    return "$prefix${authority.substringBeforeLast(':')}$path" to authority.substringAfterLast(':')
}

/** Empty (meaning the scheme's default) or 1 to 65535. */
fun isValidPort(port: String): Boolean {
    if (port.isEmpty()) return true
    val number = port.toIntOrNull() ?: return false
    return number in 1..65535
}

/** An IPv6 literal needs brackets before a port can follow it. */
private fun bracketIpv6(authority: String): String {
    val colons = authority.count { it == ':' }
    return if (colons > 1 && !authority.startsWith("[")) "[$authority]" else authority
}

private fun hasPort(authority: String) =
    if (authority.startsWith("[")) authority.substringAfter(']').startsWith(":") else ':' in authority
