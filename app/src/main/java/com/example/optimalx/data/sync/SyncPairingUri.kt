package com.example.optimalx.data.sync

data class SyncEndpoint(
    val host: String,
    val port: Int,
    val token: String,
) {
    fun statusUrl(): String = syncBaseUrl() + "/status"
    fun pushUrl(): String = syncBaseUrl() + "/push"
    fun pullUrl(): String = syncBaseUrl() + "/pull"
    fun resolveUrl(): String = syncBaseUrl() + "/resolve"

    fun filesRequestUrl(globalId: String, kind: String, relativePath: String? = null): String {
        val query = buildString {
            append("kind=")
            append(SyncUrlEncoding.encodeQueryComponent(kind))
            if (!relativePath.isNullOrBlank()) {
                append("&path=")
                append(SyncUrlEncoding.encodeQueryComponent(relativePath))
            }
        }
        return "${filesBaseUrl()}/request/$globalId?$query"
    }

    fun filesPushUrl(globalId: String): String = "${filesBaseUrl()}/push/$globalId"

    fun filesBaseUrl(): String {
        val h = host.trim().ifBlank { "127.0.0.1" }
        return "http://$h:$port/api/v1/files"
    }

    private fun syncBaseUrl(): String {
        val h = host.trim().ifBlank { "127.0.0.1" }
        return "http://$h:$port/api/v1/sync"
    }
}

internal object SyncUrlEncoding {
    fun encodeQueryComponent(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when {
                ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' || ch == '/' ->
                    sb.append(ch)
                else -> {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        sb.append('%')
                        sb.append(HEX[(b.toInt() ushr 4) and 0x0F])
                        sb.append(HEX[b.toInt() and 0x0F])
                    }
                }
            }
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}

object SyncPairingUri {

    const val SCHEME: String = "optimalx-sync"
    const val DEFAULT_PORT: Int = 7373
    private const val PREFIX = "$SCHEME://"

    fun build(host: String, port: Int, token: String): String {
        val h = host.trim()
        val t = token.trim()
        require(h.isNotEmpty()) { "host must not be blank" }
        require(port in 1..65535) { "port out of range: $port" }
        require(t.isNotEmpty()) { "token must not be blank" }
        return "$PREFIX$h:$port?token=${encodeQueryComponent(t)}"
    }

    fun parse(raw: String): SyncEndpoint? {
        val text = raw.trim()
        if (!text.startsWith(PREFIX)) return null
        val rest = text.removePrefix(PREFIX)
        val queryStart = rest.indexOf('?')
        val authority = if (queryStart >= 0) rest.substring(0, queryStart) else rest
        val query = if (queryStart >= 0) rest.substring(queryStart + 1) else ""
        val params = parseQuery(query)
        val token = params["token"]?.trim().orEmpty()
        if (token.isEmpty()) return null

        if (authority.isNotEmpty() && authority != "?") {
            val colon = authority.lastIndexOf(':')
            if (colon <= 0 || colon == authority.lastIndex) return null
            val host = authority.substring(0, colon).trim()
            val port = authority.substring(colon + 1).toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65535) return null
            return SyncEndpoint(host = host, port = port, token = token)
        }

        val host = params["host"]?.trim().orEmpty()
        val port = params["port"]?.trim()?.toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) return null
        return SyncEndpoint(host = host, port = port, token = token)
    }

    fun normalizeEndpoint(host: String, port: Int, token: String): SyncEndpoint {
        parse(host.trim())?.let { return it }
        val h = host.trim()
        val colon = h.lastIndexOf(':')
        if (colon > 0) {
            val maybePort = h.substring(colon + 1)
            if (maybePort.all { it.isDigit() }) {
                val parsedPort = maybePort.toIntOrNull()
                if (parsedPort != null && parsedPort in 1..65535) {
                    return SyncEndpoint(
                        host = h.substring(0, colon),
                        port = parsedPort,
                        token = token.trim(),
                    )
                }
            }
        }
        return SyncEndpoint(
            host = h,
            port = port,
            token = token.trim(),
        )
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = if (eq < 0) part else part.substring(0, eq)
            val value = if (eq < 0) "" else part.substring(eq + 1)
            out[decodeQueryComponent(key)] = decodeQueryComponent(value)
        }
        return out
    }

    private fun encodeQueryComponent(value: String): String = SyncUrlEncoding.encodeQueryComponent(value)

    private fun decodeQueryComponent(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val hi = hexValue(value[i + 1])
                val lo = hexValue(value[i + 2])
                if (hi >= 0 && lo >= 0) {
                    sb.append(((hi shl 4) or lo).toChar())
                    i += 3
                    continue
                }
            }
            if (ch == '+') sb.append(' ') else sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun hexValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch.code - '0'.code
        in 'A'..'F' -> ch.code - 'A'.code + 10
        in 'a'..'f' -> ch.code - 'a'.code + 10
        else -> -1
    }
}
