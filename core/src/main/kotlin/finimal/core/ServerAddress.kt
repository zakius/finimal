package finimal.core

/** Jellyfin's HTTP port out of the box. */
const val DEFAULT_JELLYFIN_PORT = "8096"

/**
 * Base URLs to try, in order, for what the login form says. A scheme is taken as written. Without one: https, then
 * http, on the port; port 443 is https alone. A port written in [server] wins over [port]; an empty [port] means the
 * scheme's own. The caller stops at a certificate problem: falling back from it would send the password in clear text.
 */
fun serverCandidates(server: String, port: String): List<String> {
    val input = server.trim().trimEnd('/')
    if (input.isEmpty()) return emptyList()
    val p = port.trim()
    val scheme = input.substringBefore("://", "").takeIf { "://" in input }
    val rest = if (scheme != null) input.substringAfter("://") else input
    val authority = rest.substringBefore('/').let(::bracketIpv6)
    val path = rest.substring(rest.substringBefore('/').length)
    fun withPort(port: String) = if (port.isEmpty()) "$authority$path" else "$authority:$port$path"
    if (scheme != null) return listOf("$scheme://" + if (hasPort(authority)) "$authority$path" else withPort(p))
    if (hasPort(authority)) return listOf("https://$authority$path", "http://$authority$path")
    if (p == "443") return listOf("https://$authority$path")
    return listOf("https://${withPort(p)}", "http://${withPort(p)}")
}

/**
 * The port to prefill for what the server field says: Jellyfin's own for a home network host (a single-label name, a
 * local suffix or an IP literal), 443 for any other domain, which is most likely behind a reverse proxy. A guess the
 * user corrects in the form; null when [server] already carries a port.
 */
fun defaultPortFor(server: String): String? {
    val rest = server.trim().substringAfter("://").substringBefore('/')
    if (hasPort(bracketIpv6(rest))) return null
    val host = rest.removePrefix("[").substringBefore(']').lowercase()
    val local = '.' !in host || ':' in host || host.all { it.isDigit() || it == '.' } ||
        listOf(".local", ".lan", ".home", ".internal", ".localhost", ".home.arpa").any(host::endsWith)
    return if (local) DEFAULT_JELLYFIN_PORT else "443"
}

/** A saved base URL back into the login form's server and port fields; nothing saved gives the default port. */
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

/** Empty (the scheme's default) or 1–65535. */
fun isValidPort(port: String) = port.isEmpty() || port.toIntOrNull()?.let { it in 1..65535 } == true

/** An IPv6 literal needs brackets before a port can follow it. */
private fun bracketIpv6(authority: String) = if (authority.count { it == ':' } > 1 && !authority.startsWith("[")) "[$authority]" else authority

private fun hasPort(authority: String) =
    if (authority.startsWith("[")) authority.substringAfter(']').startsWith(":") else ':' in authority
