package com.mastifdogo.devotion.logic

/** Pure helpers for the user-typed server address. */
object ServerAddress {
    const val DEFAULT_PORT = 8787

    /**
     * Turns what the user typed into a base URL without a trailing slash:
     * "192.168.1.50" -> "http://192.168.1.50:8787". "http://" is added when there is no scheme and
     * ":8787" when there is no port. An explicitly typed "https://" address without a port is left
     * on the HTTPS default port (it is almost certainly behind a reverse proxy).
     */
    fun normalize(raw: String): String {
        var rest = raw.trim()
        if (rest.isEmpty()) return ""
        var scheme = "http"
        var explicitHttps = false
        when {
            rest.startsWith("http://", ignoreCase = true) -> rest = rest.substring(7)
            rest.startsWith("https://", ignoreCase = true) -> {
                scheme = "https"
                explicitHttps = true
                rest = rest.substring(8)
            }
        }
        val slash = rest.indexOf('/')
        var authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash).trimEnd('/') else ""
        authority = authority.trim()
        if (authority.isEmpty()) return ""
        if (!hasPort(authority) && !explicitHttps) authority = "$authority:$DEFAULT_PORT"
        return "$scheme://$authority$path"
    }

    private fun hasPort(authority: String): Boolean {
        val hostEnd = if (authority.startsWith("[")) authority.indexOf(']') else -1
        val colon = authority.lastIndexOf(':')
        if (colon < 0 || colon < hostEnd) return false
        val port = authority.substring(colon + 1)
        return port.isNotEmpty() && port.all { it.isDigit() }
    }

    /** A file-name and preference-key safe id for a (normalized) server address. */
    fun key(address: String): String {
        val normalized = normalize(address).lowercase()
        if (normalized.isEmpty()) return "none"
        return normalized.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    }
}
