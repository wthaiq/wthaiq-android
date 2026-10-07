package com.wthaiq.signing.internal

import java.io.ByteArrayOutputStream

/**
 * A small RFC 3986 URL splitter with no Android dependency, so every rule built on it runs in JVM unit tests.
 *
 * It never throws. Paths and query values are percent-decoded the way `URLComponents` does on iOS: `%XX`
 * sequences are decoded as UTF-8 and `+` is kept as is.
 */
internal class UrlParts private constructor(
    val raw: String,
    /** Lowercased scheme, or an empty string when the URL has none. */
    val scheme: String,
    /** `true` when the URL has an authority (`//…`). */
    val hasAuthority: Boolean,
    /** The user info before `@`, or `null`. */
    val userInfo: String?,
    /** Normalized host (lowercase, no IPv6 brackets, no trailing dots), or an empty string. */
    val host: String,
    /** The explicit port, or `-1`. */
    val port: Int,
    /** The raw (percent-encoded) path. */
    val rawPath: String,
    /** The raw query without `?`, or `null`. */
    val rawQuery: String?,
    /** The raw fragment without `#`, or `null`. */
    val rawFragment: String?,
) {
    /** The percent-decoded path. */
    val path: String get() = percentDecode(rawPath)

    /** Decoded query items in order. A name without `=` has a `null` value. */
    val queryItems: List<Pair<String, String?>>
        get() {
            val query = rawQuery ?: return emptyList()
            if (query.isEmpty()) return emptyList()
            return query.split('&').filter { it.isNotEmpty() }.map { item ->
                val index = item.indexOf('=')
                if (index < 0) {
                    percentDecode(item) to null
                } else {
                    percentDecode(item.substring(0, index)) to percentDecode(item.substring(index + 1))
                }
            }
        }

    /** The first value of the query item `name`, or `null`. */
    fun queryValue(name: String): String? = queryItems.firstOrNull { it.first == name }?.second

    /** `true` when the query has an item called `name`, with or without a value. */
    fun hasQueryItem(name: String): Boolean = queryItems.any { it.first == name }

    /** `scheme://host[:port]`, the form used by `MessageEvent.origin`. */
    val origin: String
        get() {
            val hostPart = if (host.contains(':')) "[$host]" else host
            return if (port >= 0) "$scheme://$hostPart:$port" else "$scheme://$hostPart"
        }

    override fun toString(): String = raw

    companion object {
        private val pattern = Regex("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?$", RegexOption.DOT_MATCHES_ALL)
        private val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

        /** Splits `input`, or returns `null` when it is blank or contains whitespace, control characters or `\`. */
        fun parse(input: String?): UrlParts? {
            if (input.isNullOrEmpty()) return null
            if (input.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
            val match = pattern.matchEntire(input) ?: return null
            val groups = match.groupValues
            val schemeRaw = groups[2]
            if (schemeRaw.isNotEmpty() && !schemePattern.matches(schemeRaw)) return null
            val hasAuthority = groups[3].isNotEmpty()
            val authority = groups[4]
            var userInfo: String? = null
            var hostPort = authority
            val at = authority.lastIndexOf('@')
            if (at >= 0) {
                userInfo = authority.substring(0, at)
                hostPort = authority.substring(at + 1)
            }
            var host: String
            var port = -1
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                host = hostPort.substring(1, close)
                val rest = hostPort.substring(close + 1)
                if (rest.isNotEmpty()) {
                    if (!rest.startsWith(":")) return null
                    port = parsePort(rest.substring(1)) ?: return null
                }
            } else {
                val colon = hostPort.lastIndexOf(':')
                if (colon >= 0) {
                    host = hostPort.substring(0, colon)
                    port = parsePort(hostPort.substring(colon + 1)) ?: return null
                } else {
                    host = hostPort
                }
            }
            host = HostMatcher.normalize(percentDecode(host))
            return UrlParts(
                raw = input,
                scheme = schemeRaw.lowercase(),
                hasAuthority = hasAuthority,
                userInfo = userInfo,
                host = host,
                port = port,
                rawPath = groups[5],
                rawQuery = if (groups[6].isNotEmpty()) groups[7] else null,
                rawFragment = if (groups[8].isNotEmpty()) groups[9] else null,
            )
        }

        private fun parsePort(text: String): Int? {
            if (text.isEmpty()) return -1
            if (text.length > 5 || !text.all { it in '0'..'9' }) return null
            val value = text.toInt()
            return if (value in 0..65535) value else null
        }

        /** Decodes `%XX` sequences as UTF-8; malformed sequences are kept as written. `+` is not a space. */
        fun percentDecode(text: String): String {
            if (!text.contains('%')) return text
            val out = ByteArrayOutputStream(text.length)
            var index = 0
            while (index < text.length) {
                if (text[index] == '%' && index + 2 < text.length) {
                    val high = Character.digit(text[index + 1], 16)
                    val low = Character.digit(text[index + 2], 16)
                    if (high >= 0 && low >= 0) {
                        out.write(high * 16 + low)
                        index += 3
                        continue
                    }
                }
                val codePoint = text.codePointAt(index)
                val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                index += Character.charCount(codePoint)
            }
            return out.toString(Charsets.UTF_8.name())
        }

        /** Percent-encodes a query value the way `encodeURIComponent` does. */
        fun percentEncode(text: String): String {
            val builder = StringBuilder()
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val value = byte.toInt() and 0xFF
                val char = value.toChar()
                if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.!~*'()") {
                    builder.append(char)
                } else {
                    builder.append('%').append("0123456789ABCDEF"[value shr 4]).append("0123456789ABCDEF"[value and 0xF])
                }
            }
            return builder.toString()
        }
    }
}
